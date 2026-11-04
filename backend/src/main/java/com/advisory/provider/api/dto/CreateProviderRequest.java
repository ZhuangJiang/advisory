package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/providers}; {@code apiKey} is optional and is encrypted before it is
 * stored.
 */
public record CreateProviderRequest(
        @NotBlank(message = "name must not be blank") @Size(max = 128) String name,
        @NotNull(message = "type must not be null") ProviderType type,
        @NotBlank(message = "baseUrl must not be blank") @Size(max = 512) String baseUrl,
        String apiKey) {
}
