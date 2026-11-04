package com.advisory.agent.domain;

import java.time.Instant;

/**
 * A read-only row of {@code agent_versions}: the snapshot of one agent at one version.
 *
 * <p>Rows are only ever inserted (by create = version 1 and by every successful PATCH); nothing
 * updates or deletes them except the agent's own cascade, which is what makes historical
 * conversations reproducible.</p>
 */
public record AgentVersion(long id, long agentId, int version, AgentSnapshot snapshot, Instant createdAt) {
}
