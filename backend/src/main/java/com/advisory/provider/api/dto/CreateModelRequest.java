package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ModelCapability;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/providers/{id}/models}. {@code EMBEDDING} requires
 * {@code dimension == app.embedding.dimension} (otherwise 40010); {@code CHAT} must omit
 * {@code dimension}.
 */
public record CreateModelRequest(
        @NotBlank(message = "modelName must not be blank") @Size(max = 255) String modelName,
        @NotNull(message = "capability must not be null") ModelCapability capability,
        Integer dimension) {
}
