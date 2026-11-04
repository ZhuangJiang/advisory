package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ProviderType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Provider payload. The ciphertext is never a field here: {@code apiKeyMasked} is the first four
 * characters of the <em>decrypted</em> key plus {@code ****}, and the field is omitted entirely
 * ({@link JsonInclude#NON_NULL}) when the provider has no usable key.
 */
public record ProviderResponse(
        Long id,
        String name,
        ProviderType type,
        String baseUrl,
        boolean enabled,
        Instant createdAt,
        long modelCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String apiKeyMasked) {
}
