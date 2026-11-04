package com.advisory.chat.api.dto;

import com.advisory.chat.domain.Message;
import com.advisory.chat.domain.MessageStatus;
import com.advisory.chat.domain.ReasoningStep;
import com.advisory.chat.orchestration.TokenUsage;
import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/messages/{id}} 的返回体：单条回答的权威视图。
 *
 * <p>SSE 断线后客户端就调这个接口：{@code steps} 与 {@code citations} 取自已落库的数据而非重放，
 * 因此一次被中断的流总能被精确还原（spec §9、§11）。</p>
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
