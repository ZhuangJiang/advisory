package com.advisory.chat.service;

import com.advisory.chat.domain.MessageStatus;
import com.advisory.chat.orchestration.Phase;
import com.advisory.chat.orchestration.StepSink;
import com.advisory.chat.orchestration.TokenUsage;
import com.advisory.chat.repo.MessageRepository;
import com.advisory.knowledge.retrieval.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把一次编排运行流式推给浏览器，并把首次状态变化同步到数据库。
 *
 * <p>事件名与载荷严格遵循 spec §11：{@code meta}（首帧，重连凭据）、{@code step}、{@code delta}、
 * {@code citations}、{@code done}、{@code error}，另外每 15 秒一个 {@code ping}。所有帧都在同一把锁后串行写出，
 * 因为心跳线程与编排线程会同时写同一个 emitter——没有锁，一个 ping 就可能插进某帧中间，前端解析随即错位。</p>
 *
 * <p>客户端断开不会毁掉这次运行：第一次发送失败就把 {@link #closed} 置位，之后所有钩子变成空操作，
 * 编排仍会跑完并落库步骤（spec §9——步骤是主数据，SSE 只是实时通道）。</p>
 */
public class SseStepSink implements StepSink {

    private static final Logger log = LoggerFactory.getLogger(SseStepSink.class);

    public static final long TIMEOUT_MILLIS = 180_000L;
    static final long PING_INTERVAL_MILLIS = 15_000L;

    private final SseEmitter emitter;
    private final MessageRepository messages;
    private final ObjectMapper objectMapper;
    private final long messageId;
    private final Object sendLock = new Object();
    private final AtomicBoolean streamingMarked = new AtomicBoolean();
    private final AtomicBoolean closedOnce = new AtomicBoolean();
    private final ScheduledFuture<?> ping;

    private volatile boolean closed;

    public SseStepSink(SseEmitter emitter, MessageRepository messages, ObjectMapper objectMapper,
                       long messageId, ScheduledExecutorService heartbeat) {
        this.emitter = emitter;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.messageId = messageId;
        this.ping = heartbeat.scheduleAtFixedRate(this::ping, PING_INTERVAL_MILLIS,
                PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** 必须是首帧：前端要存下这个 id，断线后才能凭它重连拉取完整结果。 */
    public void meta(long conversationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("conversationId", conversationId);
        send("meta", payload);
    }

    /** 推送步骤开始帧。 */
    @Override
    public void onStepStart(int round, Phase phase) {
        step(round, phase, "started", null);
    }

    /** 推送步骤结束帧。 */
    @Override
    public void onStepEnd(int round, Phase phase, String summary) {
        step(round, phase, "finished", summary);
    }

    /** 推送增量文本。 */
    @Override
    public void onDelta(Phase phase, String text) {
        if (streamingMarked.compareAndSet(false, true)) {
            try {
                messages.updateStatus(messageId, MessageStatus.STREAMING);
            } catch (RuntimeException e) {
                log.warn("could not mark message {} as STREAMING: {}", messageId, e.getMessage());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase.name());
        payload.put("text", text);
        send("delta", payload);
    }

    /** 推送累积去重后的证据列表。 */
    @Override
    public void onCitations(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (chunks != null) {
            for (RetrievedChunk chunk : chunks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunkId", chunk.chunkId());
                item.put("documentId", chunk.documentId());
                item.put("docName", chunk.docName());
                item.put("nodeName", chunk.nodeName());
                item.put("snippet", chunk.content());
                item.put("score", chunk.score());
                items.add(item);
            }
        }
        send("citations", Map.of("items", items));
    }

    /** 推送终帧并结束流。 */
    public void done(MessageStatus status, TokenUsage usage, long latencyMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("status", status.name());
        payload.put("tokenUsage", usage);
        payload.put("latencyMs", latencyMs);
        send("done", payload);
    }

    /** 推送错误帧并结束流；响应已提交时只记日志。 */
    public void error(int code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("recoverable", false);
        send("error", payload);
    }

    /** 结束事件流。可重复调用（完成回调与超时回调都可能触发）。 */
    public void close() {
        if (!closedOnce.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        ping.cancel(false);
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("SSE emitter for message {} was already completed", messageId);
        }
    }

    // ------------------------------------------------------------------ 传输层

    private void step(int round, Phase phase, String status, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("round", round);
        payload.put("phase", phase.name());
        payload.put("status", status);
        payload.put("summary", summary);
        send("step", payload);
    }

    private void ping() {
        if (closed) {
            return;
        }
        synchronized (sendLock) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("ping").data("{}", MediaType.APPLICATION_JSON));
            } catch (IOException | RuntimeException e) {
                closed = true; // 客户端已断开：编排继续跑完，流不再写
                log.debug("心跳写入失败，消息 {} 的客户端已断开: {}", messageId, e.getMessage());
            }
        }
    }

    private void send(String event, Object payload) {
        if (closed) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("SSE 载荷序列化失败，事件 {}: {}", event, e.getMessage());
            return;
        }
        synchronized (sendLock) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
            } catch (IOException | RuntimeException e) {
                closed = true;
                log.debug("SSE client for message {} disconnected: {}", messageId, e.getMessage());
            }
        }
    }
}
