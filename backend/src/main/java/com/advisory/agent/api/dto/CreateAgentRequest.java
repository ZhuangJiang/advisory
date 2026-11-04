package com.advisory.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/agents}.
 *
 * <p>{@code embeddingModelId} is deliberately absent: it is derived from the referenced knowledge
 * bases so a client cannot make the stored value disagree with them. Optional fields fall back to
 * the schema defaults (maxRounds 3, topK 20, temperature 0.70, enabled true).</p>
 */
public record CreateAgentRequest(
        @NotBlank @Size(max = 128) String name,
        String description,
        @NotBlank String systemPrompt,
        @NotNull Long chatModelId,
        @NotEmpty List<Long> kbIds,
        Map<Long, List<Long>> nodeScope,
        Integer maxRounds,
        Integer topK,
        BigDecimal temperature,
        Boolean enabled) {
}
