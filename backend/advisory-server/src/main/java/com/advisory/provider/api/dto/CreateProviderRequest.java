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
        @NotBlank(message = "名称不能为空") @Size(max = 128) String name,
        @NotNull(message = "必须指定提供商类型") ProviderType type,
        @NotBlank(message = "接口地址不能为空") @Size(max = 512) String baseUrl,
        String apiKey) {
}
