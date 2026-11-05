package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ModelCapability;
import com.advisory.provider.domain.ModelConfig;

import java.time.Instant;

/**
 * 本地 {@code models} 行的返回体。
 *
 * <p>{@code params} 是 jsonb 列的原始 JSON 字符串，直接透传以便前端按需解析。</p>
 */
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
