package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ProviderType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 提供商的返回体。
 *
 * <p>密文永远不会出现在这里：{@code apiKeyMasked} 是<em>解密后</em>密钥的前四位加 {@code ****}；
 * 提供商没有可用密钥时该字段整体省略（{@link JsonInclude#NON_NULL}），避免前端把空串当成「已配置」。</p>
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
