package com.advisory.chat.api.dto;

import com.advisory.chat.domain.Conversation;

import java.time.Instant;

/**
 * Conversation row for the sidebar list. {@code agentVersion} is the locked version.
 *
 * <p>{@code userId} is exposed for one reason: an administrator's list is unfiltered (spec §8), so
 * the client must know which rows are not theirs in order to render them read-only instead of
 * offering a composer the server would reject with 403.</p>
 */
public record ConversationResponse(long id, long agentId, String agentName, int agentVersion,
                                   long userId, String title, Instant createdAt, Instant updatedAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(conversation.id(), conversation.agentId(),
                conversation.agentName(), conversation.agentVersion(), conversation.userId(),
                conversation.title(), conversation.createdAt(), conversation.updatedAt());
    }
}
