package com.advisory.chat.api.dto;

import com.advisory.chat.domain.Conversation;

import java.time.Instant;

/** Conversation row for the sidebar list. {@code agentVersion} is the locked version. */
public record ConversationResponse(long id, long agentId, String agentName, int agentVersion,
                                   String title, Instant createdAt, Instant updatedAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(conversation.id(), conversation.agentId(),
                conversation.agentName(), conversation.agentVersion(), conversation.title(),
                conversation.createdAt(), conversation.updatedAt());
    }
}
