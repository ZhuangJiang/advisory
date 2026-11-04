package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ProviderType;

/**
 * Body of {@code PATCH /api/providers/{id}}: every field is optional. {@code apiKey} semantics:
 * absent = unchanged, {@code ""} = clear the stored key, any other value = re-encrypt.
 */
public record PatchProviderRequest(
        String name,
        ProviderType type,
        String baseUrl,
        String apiKey,
        Boolean enabled) {
}
