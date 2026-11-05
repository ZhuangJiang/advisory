package com.advisory.provider.api.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/models/{id}} 的请求体：所有字段可选，{@code null} 表示不变。
 *
 * <p>{@code capability} 不可修改；传入的 {@code dimension} 会按库中该行的用途重新校验
 * （向量模型必须等于配置维度，对话模型不允许携带）。</p>
 */
public record PatchModelRequest(
        @Size(max = 255) String modelName,
        Integer dimension,
        Boolean enabled) {
}
