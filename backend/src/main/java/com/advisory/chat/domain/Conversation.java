package com.advisory.chat.domain;

import java.time.Instant;

/**
 * A row of {@code conversations} — one chat thread between a user and an agent.
 *
 * @param agentVersion the agent version <em>locked when the conversation was created</em>. Every
 *                     question in this conversation re-reads that snapshot, so editing the agent
 *                     later never changes the behaviour (or the answers) of an existing thread
 *                     (spec §9). {@code agentName} is joined for display only.
 */
public record Conversation(long id, long agentId, String agentName, int agentVersion, long userId,
                           String title, Instant createdAt, Instant updatedAt) {
}
