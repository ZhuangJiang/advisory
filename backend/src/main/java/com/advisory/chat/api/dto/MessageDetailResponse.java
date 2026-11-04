package com.advisory.chat.api.dto;

import com.advisory.chat.domain.Message;
import com.advisory.chat.domain.MessageStatus;
import com.advisory.chat.domain.ReasoningStep;
import com.advisory.chat.orchestration.TokenUsage;
import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/messages/{id}} — the authoritative view of one answer.
 *
 * <p>This is what a client calls after an SSE disconnect: {@code steps} and {@code citations} are
 * the persisted copies, not a replay, so a partial stream can always be reconstructed exactly
 * (spec §9, §11).</p>
 */
public record MessageDetailResponse(long id, long conversationId, String role, MessageStatus status,
                                    String content, List<RetrievedChunk> citations,
                                    List<ReasoningStepResponse> steps, TokenUsage tokenUsage,
                                    Long latencyMs, Instant createdAt) {

    public static MessageDetailResponse of(Message message, List<ReasoningStep> steps) {
        return new MessageDetailResponse(message.id(), message.conversationId(), message.role(),
                message.status(), message.content(), message.citations(),
                steps.stream().map(ReasoningStepResponse::from).toList(),
                message.tokenUsage(), message.latencyMs(), message.createdAt());
    }
}
