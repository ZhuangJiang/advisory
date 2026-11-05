package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ModelCapability;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/providers/{id}/models} 的请求体。
 *
 * <p>{@code EMBEDDING} 必须满足 {@code dimension == app.embedding.dimension}（否则返回 40010）；
 * {@code CHAT} 必须不填 {@code dimension}，因为对话模型没有维度概念。</p>
 */
public record CreateModelRequest(
        @NotBlank(message = "模型名称不能为空") @Size(max = 255) String modelName,
        @NotNull(message = "必须指定模型类型") ModelCapability capability,
        Integer dimension) {
}
