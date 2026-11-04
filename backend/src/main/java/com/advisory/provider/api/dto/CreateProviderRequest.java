package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/providers} 的请求体。
 *
 * <p>{@code apiKey} 可选，落库前会先加密；内网无需鉴权的 Ollama 可以不填。</p>
 */
public record CreateProviderRequest(
        @NotBlank(message = "name must not be blank") @Size(max = 128) String name,
        @NotNull(message = "type must not be null") ProviderType type,
        @NotBlank(message = "baseUrl must not be blank") @Size(max = 512) String baseUrl,
        String apiKey) {
}
