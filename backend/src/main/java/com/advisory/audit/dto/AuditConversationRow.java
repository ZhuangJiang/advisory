package com.advisory.audit.dto;

import java.time.Instant;

/**
 * One conversation in the administrator's audit list.
 *
 * <p>Denormalised on purpose: the audit page shows a flat table, and the alternative — a client
 * joining users, agents and messages itself — is not possible from the API. {@code messageCount} and
 * {@code lastMessageAt} are computed in SQL from {@code messages}, so a thread with no answer yet
 * still appears with {@code lastMessageAt = null}.</p>
 */
public record AuditConversationRow(long id, long userId, String userName, long agentId,
                                   String agentName, int agentVersion, String title,
                                   long messageCount, Instant createdAt, Instant lastMessageAt) {
}
