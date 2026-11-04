package com.advisory.agent.api.dto;

import com.advisory.agent.domain.Agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Agent payload for list/detail/create/update. {@code currentVersion} is the live version number. */
public record AgentResponse(
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

    public static AgentResponse from(Agent agent) {
        return new AgentResponse(
                agent.id(),
                agent.name(),
                agent.description(),
                agent.systemPrompt(),
                agent.chatModelId(),
                agent.embeddingModelId(),
                agent.kbIds(),
                agent.nodeScope(),
                agent.maxRounds(),
                agent.topK(),
                agent.temperature(),
                agent.enabled(),
                agent.currentVersion(),
                agent.createdAt(),
                agent.updatedAt());
    }
}
