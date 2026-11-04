package com.advisory.agent.api.dto;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code PATCH /api/agents/{id}} — every field is optional; {@code null} means "leave unchanged".
 *
 * <p>{@code description} may be reset by sending an empty string (normalized to {@code null}). The
 * derived {@code embeddingModelId} is recomputed from the resulting {@code kbIds}; sending it is
 * pointless and it is not part of this request.</p>
 */
public record UpdateAgentRequest(
        @Size(max = 128) String name,
        String description,
        String systemPrompt,
        Long chatModelId,
        List<Long> kbIds,
        Map<Long, List<Long>> nodeScope,
        Integer maxRounds,
        Integer topK,
        BigDecimal temperature,
        Boolean enabled) {
}
