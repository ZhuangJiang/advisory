package com.advisory.chat.domain;

import com.advisory.chat.orchestration.Phase;

import java.time.Instant;

/**
 * A row of {@code reasoning_steps}.
 *
 * <p>The table has no {@code summary} column, so the orchestrator's {@link Phase} step is stored as
 * {@code payload = {"summary": ..., "detail": <the step's JSON as a string>}}. That keeps the
 * human-readable line and the machine-readable payload in one place without a migration, and
 * {@code detail} stays a string so a payload that is not valid JSON can never fail the insert.</p>
 */
public record ReasoningStep(long id, long messageId, int round, Phase phase, String summary,
                            String payload, long latencyMs, Instant createdAt) {
}
