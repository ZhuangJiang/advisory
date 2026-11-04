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
 * Conversations, their messages, and the SSE entry point of a question.
 *
 * <p>Request/response methods are transactional reads; {@link #start} deliberately is <em>not</em>
 * transactional. It writes the USER message and the {@code PENDING} assistant row in autocommit,
 * then hands the orchestration to a worker thread — inside a transaction the worker could start
 * before the rows are visible, which would turn an accepted question into a spurious failure.</p>
 *
 * <p>Access (spec §12): a {@code USER} only ever sees their own conversations and messages; an
 * {@code ADMIN} sees everything. "Not yours" and "does not exist" are both 404 — distinguishing them
 * would let a user probe for ids.</p>
 *
 * <p>Failure semantics: the orchestrator returns an {@link OrchestrationResult} for every degraded
 * but completed run ({@code TRUNCATED}); a provider error surfaces as an {@link AppException} and is
 * stored as {@code FAILED} with the readable message. In both cases the steps that ran are
 * persisted, and the conversation lock is released in a {@code finally}.</p>
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

    // ------------------------------------------------------------------ conversations

    /**
     * Creates a thread that is pinned to the agent's <em>current</em> version. A disabled agent
     * cannot be used for new questions, but existing conversations of a since-disabled agent still
     * resolve their locked snapshot and keep working.
     */
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

    @Transactional(readOnly = true)
    public List<MessageSummaryResponse> listMessages(Long conversationId, CurrentUser user) {
        requireConversation(conversationId, user.id(), user.role());
        return messages.findByConversation(conversationId).stream()
                .map(MessageSummaryResponse::from)
                .toList();
    }

    /** The reconnect path: everything a disconnected client needs, straight from the database. */
    @Transactional(readOnly = true)
    public MessageDetailResponse getMessage(Long messageId, CurrentUser user) {
        Message message = messages.findById(messageId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Message not found"));
        requireConversation(message.conversationId(), user.id(), user.role());
        return MessageDetailResponse.of(message, steps.findByMessage(messageId));
    }

    // ------------------------------------------------------------------ ask

    /**
     * Accepts one question and returns the stream. The first frame is {@code meta} with the
     * assistant message id, sent before the worker starts so it cannot be overtaken by a step event.
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
        // An administrator may read any thread (that is how the audit view works) but must not answer
        // on someone else's behalf: messages would be attributed to the wrong person (spec §8).
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
                // A saturated pool must not leave an eternal PENDING row behind.
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

    // ------------------------------------------------------------------ worker

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

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves the conversation and enforces ownership. A {@code USER} asking for someone else's
     * conversation gets the same 404 as a missing id.
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
