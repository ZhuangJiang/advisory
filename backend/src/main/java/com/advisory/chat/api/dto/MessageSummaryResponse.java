package com.advisory.chat.api.dto;

import com.advisory.chat.domain.Message;
import com.advisory.chat.domain.MessageStatus;

import java.time.Instant;

/** Lightweight message row for {@code GET /api/conversations/{id}/messages}. */
public record MessageSummaryResponse(long id, long conversationId, String role, MessageStatus status,
                                     String content, int citationCount, Instant createdAt) {

    public static MessageSummaryResponse from(Message message) {
        return new MessageSummaryResponse(message.id(), message.conversationId(), message.role(),
                message.status(), message.content(), message.citations().size(),
                message.createdAt());
    }
}
