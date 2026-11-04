package com.advisory.agent.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A row of {@code agents} — the current configuration of an agent plus its version counter.
 *
 * <p>Built from a direct JDBC read (see {@code AgentRepository}); the live row and the snapshot at
 * {@code currentVersion} are written in the same transaction, so {@link #toSnapshot()} and the
 * stored version row always describe the same configuration.</p>
 */
public record Agent(
        long id,
        String name,
        String description,
        String systemPrompt,
        Long chatModelId,
        Long embeddingModelId,
        List<Long> kbIds,
        Map<Long, List<Long>> nodeScope,
        int maxRounds,
        int topK,
        BigDecimal temperature,
        boolean enabled,
        int currentVersion,
        Instant createdAt,
        Instant updatedAt) {

    public AgentSnapshot toSnapshot() {
        return new AgentSnapshot(name, systemPrompt, chatModelId, embeddingModelId, kbIds,
                nodeScope, maxRounds, topK, temperature);
    }
}
