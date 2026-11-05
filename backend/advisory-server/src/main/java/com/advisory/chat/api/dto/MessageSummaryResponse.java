package com.advisory.chat.api.dto;

import com.advisory.chat.domain.Message;
import com.advisory.chat.domain.MessageStatus;

import java.time.Instant;

/** {@code GET /api/conversations/{id}/messages} 使用的轻量消息行，不含步骤与引用明细。 */
public record MessageSummaryResponse(long id, long conversationId, String role, MessageStatus status,
                                     String content, int citationCount, Instant createdAt) {

    public static MessageSummaryResponse from(Message message) {
        return new MessageSummaryResponse(message.id(), message.conversationId(), message.role(),
                message.status(), message.content(), message.citations().size(),
                message.createdAt());
    }
}
