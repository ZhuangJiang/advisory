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
 * Streams one orchestration run to the browser and mirrors the first status change to the database.
 *
 * <p>Event names and payloads follow spec §11 exactly: {@code meta} (first frame, the reconnect
 * handle), {@code step}, {@code delta}, {@code citations}, {@code done}, {@code error}, plus a
 * {@code ping} every 15s. All frames are serialized behind one lock because the heartbeat thread and
 * the orchestration thread write to the same emitter; without it a ping can interleave inside a
 * frame.</p>
 *
 * <p>A dead client never breaks the run: the first failed send flips {@link #closed} and every later
 * hook becomes a no-op, while the orchestration continues to completion and persists its steps
 * (spec §9 — steps are the master data, SSE is only the live channel).</p>
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

    /** Must be the first frame: the frontend stores the id and reconnects through it. */
    public void meta(long conversationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("conversationId", conversationId);
        send("meta", payload);
    }

    @Override
    public void onStepStart(int round, Phase phase) {
        step(round, phase, "started", null);
    }

    @Override
    public void onStepEnd(int round, Phase phase, String summary) {
        step(round, phase, "finished", summary);
    }

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

    public void done(MessageStatus status, TokenUsage usage, long latencyMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("status", status.name());
        payload.put("tokenUsage", usage);
        payload.put("latencyMs", latencyMs);
        send("done", payload);
    }

    public void error(int code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("recoverable", false);
        send("error", payload);
    }

    /** Terminates the stream. Safe to call twice (completion/timeout callbacks included). */
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

    // ------------------------------------------------------------------ transport

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
                closed = true; // client gone; the run keeps going, the stream does not
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
            log.warn("could not serialize SSE payload for {}: {}", event, e.getMessage());
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
