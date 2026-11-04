package com.advisory.chat.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/conversations}. The created conversation locks the agent's current version, so a
 * later edit of the agent never changes an existing thread.
 */
public record CreateConversationRequest(@NotNull Long agentId, String title) {
}
