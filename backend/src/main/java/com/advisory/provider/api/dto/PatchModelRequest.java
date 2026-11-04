package com.advisory.provider.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/models/{id}}: every field is optional. {@code capability} is immutable;
 * a provided {@code dimension} is re-checked against the row's capability (EMBEDDING must be 1024,
 * CHAT may not carry one).
 */
public record PatchModelRequest(
        @Size(max = 255) String modelName,
        Integer dimension,
        Boolean enabled) {
}
