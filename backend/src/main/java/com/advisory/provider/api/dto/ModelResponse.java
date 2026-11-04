package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ModelCapability;
import com.advisory.provider.domain.ModelConfig;

import java.time.Instant;

/** Local {@code models} row payload ({@code params} is the raw JSON string of the jsonb column). */
public record ModelResponse(
        Long id,
        Long providerId,
        String modelName,
        ModelCapability capability,
        Integer dimension,
        String params,
        boolean enabled,
        Instant createdAt) {

    public static ModelResponse from(ModelConfig model) {
        return new ModelResponse(
                model.getId(),
                model.getProviderId(),
                model.getModelName(),
                model.getCapability(),
                model.getDimension(),
                model.getParams(),
                model.isEnabled(),
                model.getCreatedAt());
    }
}
