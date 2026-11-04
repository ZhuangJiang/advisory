package com.advisory.agent.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable configuration of an agent at one point in time.
 *
 * <p>This record <em>is</em> the JSON stored in {@code agent_versions.snapshot} and the structure
 * both the live {@code agents} row and every historical version resolve to, so a conversation can
 * be replayed with exactly the settings it was started with. {@code embeddingModelId} is never sent
 * by a client: it is derived from the knowledge bases and stored redundantly to detect drift at
 * run time (spec §9).</p>
 *
 * <p>{@code nodeScope} maps a knowledge base id to the node ids that scope it; an empty list means
 * "the whole knowledge base". The canonical constructor defensively copies both collections so a
 * snapshot read back from the database can never be mutated by its consumer.</p>
 */
public record AgentSnapshot(
        String name,
        String systemPrompt,
        Long chatModelId,
        Long embeddingModelId,
        List<Long> kbIds,
        Map<Long, List<Long>> nodeScope,
        int maxRounds,
        int topK,
        BigDecimal temperature) {

    public AgentSnapshot {
        kbIds = kbIds == null ? List.of() : List.copyOf(kbIds);
        nodeScope = immutableCopy(nodeScope);
    }

    private static Map<Long, List<Long>> immutableCopy(Map<Long, List<Long>> scope) {
        if (scope == null || scope.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> copy = new LinkedHashMap<>();
        scope.forEach((kbId, nodeIds) ->
                copy.put(kbId, nodeIds == null ? List.of() : List.copyOf(nodeIds)));
        return Collections.unmodifiableMap(copy);
    }
}
