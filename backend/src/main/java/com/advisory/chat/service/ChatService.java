package com.advisory.chat.service;

import com.advisory.agent.domain.Agent;
import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.agent.repo.AgentRepository;
import com.advisory.agent.service.AgentService;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.security.CurrentUser;
import com.advisory.chat.api.dto.ConversationResponse;
import com.advisory.chat.api.dto.MessageDetailResponse;
import com.advisory.chat.api.dto.MessageSummaryResponse;
import com.advisory.chat.domain.Conversation;
import com.advisory.chat.domain.Message;
import com.advisory.chat.domain.MessageStatus;
import com.advisory.chat.orchestration.ChatOrchestrator;
import com.advisory.chat.orchestration.OrchestrationResult;
import com.advisory.chat.repo.ConversationRepository;
import com.advisory.chat.repo.MessageRepository;
import com.advisory.chat.repo.ReasoningStepRepository;
import com.advisory.common.api.PageResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 会话、消息，以及提问的 SSE 入口。
 *
 * <p>请求/响应类方法都是只读事务；{@link #start} 则刻意<em>不</em>加事务：它以自动提交方式写入用户消息与
 * {@code PENDING} 的助手行，然后把编排交给工作线程。若包在事务里，工作线程可能在这些行可见之前就启动，
 * 一个已被接受的提问会因此变成莫名其妙的失败。</p>
 *
 * <p>访问控制（spec §12）：{@code USER} 只能看到自己的会话与消息，{@code ADMIN} 可以看到全部。
 * 「不属于你」与「不存在」都返回 404——一旦区分开，用户就能靠状态码枚举出他人的会话 id。</p>
 *
 * <p>失败语义：降级但跑完的运行由编排器返回 {@link OrchestrationResult}（{@code TRUNCATED}）；
 * 提供商报错则以 {@link AppException} 冒出来，被记为 {@code FAILED} 并保存可读信息。两种情况下真正跑过的步骤
 * 都会落库，会话锁在 {@code finally} 里释放。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUESTION_LENGTH = 8000;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ReasoningStepRepository steps;
    private final AgentRepository agents;
    private final AgentService agentService;
    private final ChatOrchestrator orchestrator;
    private final ConversationLock lock;
    private final ObjectMapper objectMapper;
    private final ExecutorService workers;
    private final ScheduledExecutorService heartbeat;

    public ChatService(ConversationRepository conversations, MessageRepository messages,
                       ReasoningStepRepository steps, AgentRepository agents,
                       AgentService agentService, ChatOrchestrator orchestrator,
                       ConversationLock lock, ObjectMapper objectMapper,
                       @Qualifier("chatWorkerExecutor") ExecutorService workers,
                       @Qualifier("chatHeartbeatScheduler") ScheduledExecutorService heartbeat) {
        this.conversations = conversations;
        this.messages = messages;
        this.steps = steps;
        this.agents = agents;
        this.agentService = agentService;
        this.orchestrator = orchestrator;
        this.lock = lock;
        this.objectMapper = objectMapper;
        this.workers = workers;
        this.heartbeat = heartbeat;
    }

    // ------------------------------------------------------------------ 会话

    /**
     * 新建会话，并把它钉在 Agent 的<em>当前</em>版本上。
     *
     * <p>已停用的 Agent 不能用于新建会话，但停用之前就已存在的会话仍会解析自己锁定的快照继续可用——
     * 停用影响的是「还能不能开始新对话」，而不是「旧对话是否作废」。</p>
     *
     * @param agentId 目标 Agent
     * @param userId  发起者
     * @return 新建的会话
     */
    /** 新建会话并钉住 Agent 的当前版本。 */
    @Transactional
    public ConversationResponse createConversation(Long agentId, Long userId, String title) {
        if (agentId == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "agentId is required");
        }
        Agent agent = agents.findById(agentId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent not found"));
        if (!agent.enabled()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "agent is disabled");
        }
        long id = conversations.insert(agent.id(), agent.currentVersion(), userId,
                normalizeTitle(title));
        return ConversationResponse.from(requireConversation(id, userId, UserRole.USER));
    }

    /** 会话列表；普通用户只能看到自己的。 */
    @Transactional(readOnly = true)
    public PageResponse<ConversationResponse> listConversations(CurrentUser user, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        Long userId = user.role() == UserRole.ADMIN ? null : user.id();
        List<ConversationResponse> items = conversations.findPage(userId, pageSize,
                        pageIndex * pageSize).stream()
                .map(ConversationResponse::from)
                .toList();
        return PageResponse.of(items, pageIndex, pageSize, conversations.count(userId));
    }

    /** 会话内的消息列表。 */
    @Transactional(readOnly = true)
    public List<MessageSummaryResponse> listMessages(Long conversationId, CurrentUser user) {
        requireConversation(conversationId, user.id(), user.role());
        return messages.findByConversation(conversationId).stream()
                .map(MessageSummaryResponse::from)
                .toList();
    }

    /** 重连路径：断线客户端需要的一切，直接从数据库读取。 */
    @Transactional(readOnly = true)
    public MessageDetailResponse getMessage(Long messageId, CurrentUser user) {
        Message message = messages.findById(messageId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Message not found"));
        requireConversation(message.conversationId(), user.id(), user.role());
        return MessageDetailResponse.of(message, steps.findByMessage(messageId));
    }

    // ------------------------------------------------------------------ 提问

    /**
     * 接受一个问题并返回事件流。
     *
     * <p>首帧是携带助手消息 id 的 {@code meta}，必须在工作线程启动之前发出，否则可能被某个步骤事件抢在前面，
     * 前端就拿不到重连用的 id 了。</p>
     *
     * @param conversationId 会话主键
     * @param question       用户问题
     * @return SSE 发射器
     */
    public SseEmitter start(Long conversationId, CurrentUser user, String content) {
        String question = content == null ? "" : content.trim();
        if (question.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "content must not be blank");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "content must not exceed " + MAX_QUESTION_LENGTH + " characters");
        }
        Conversation conversation = requireConversation(conversationId, user.id(), user.role());
        // 管理员可以读任意会话（审计视图正是如此），但不能替别人提问：
        // 那样消息会被归到错误的人头上（spec §8）。
        if (user.role() == UserRole.ADMIN && conversation.userId() != user.id()) {
            throw new AppException(ErrorCode.FORBIDDEN, "管理员不能代替用户提问");
        }
        if (!lock.tryAcquire(conversationId)) {
            throw new AppException(ErrorCode.CONVERSATION_BUSY,
                    "该会话已有进行中的提问，请等待当前回答完成");
        }
        boolean handedOff = false;
        try {
            messages.insert(conversationId, "USER", question, MessageStatus.COMPLETED);
            long assistantId = messages.insert(conversationId, "ASSISTANT", null, MessageStatus.PENDING);

            SseEmitter emitter = new SseEmitter(SseStepSink.TIMEOUT_MILLIS);
            SseStepSink sink = new SseStepSink(emitter, messages, objectMapper, assistantId, heartbeat);
            emitter.onCompletion(sink::close);
            emitter.onTimeout(sink::close);
            emitter.onError(error -> sink.close());
            sink.meta(conversationId);

            try {
                workers.execute(() -> answer(conversation, assistantId, question, sink));
            } catch (RuntimeException rejected) {
                // 线程池满时不能让那条 PENDING 行永远挂着，必须就地失败并释放锁。
                messages.fail(assistantId, "系统繁忙，请稍后重试", 0);
                throw rejected;
            }
            handedOff = true;
            return emitter;
        } finally {
            if (!handedOff) {
                lock.release(conversationId);
            }
        }
    }

    // ------------------------------------------------------------------ 工作线程

    private void answer(Conversation conversation, long assistantId, String question,
                        SseStepSink sink) {
        long startedAt = System.nanoTime();
        try {
            AgentSnapshot snapshot = agentService.resolveSnapshot(conversation.agentId(),
                    conversation.agentVersion());
            OrchestrationResult result = orchestrator.run(snapshot, question, sink);
            long latencyMs = elapsedMillis(startedAt);
            MessageStatus status = result.truncated() ? MessageStatus.TRUNCATED : MessageStatus.COMPLETED;
            steps.insertAll(assistantId, result.steps());
            messages.complete(assistantId, result.answer(), result.citations(), result.usage(),
                    latencyMs, status);
            conversations.touch(conversation.id());
            sink.done(status, result.usage(), latencyMs);
        } catch (RuntimeException e) {
            long latencyMs = elapsedMillis(startedAt);
            int code = e instanceof AppException app ? app.getCode().getCode()
                    : ErrorCode.INTERNAL_ERROR.getCode();
            String reason = e.getMessage() == null ? "生成回答失败" : e.getMessage();
            log.warn("question on conversation {} failed: {}", conversation.id(), reason);
            try {
                messages.fail(assistantId, reason, latencyMs);
            } catch (RuntimeException persistFailure) {
                log.error("could not mark message {} as FAILED", assistantId, persistFailure);
            }
            sink.error(code, reason);
        } finally {
            lock.release(conversation.id());
            sink.close();
        }
    }

    // ------------------------------------------------------------------ 辅助方法

    /**
     * 取出会话并校验归属。
     *
     * <p>普通用户访问他人会话时得到的 404 与「id 不存在」完全一致，不给出任何可用于枚举的差异。</p>
     *
     * @param conversationId 会话主键
     * @param userId         调用者
     * @param role           调用者角色
     * @return 会话
     */
    private Conversation requireConversation(Long conversationId, Long userId, UserRole role) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Conversation not found"));
        if (role != UserRole.ADMIN && conversation.userId() != userId) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Conversation not found");
        }
        return conversation;
    }

    private static String normalizeTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}
