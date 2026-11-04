package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ProviderType;

/**
 * {@code PATCH /api/providers/{id}} 的请求体：所有字段可选。
 *
 * <p>{@code apiKey} 有三种语义：字段缺省表示保持不变，空串表示清空已存密钥，其他值表示重新加密写入。
 * 这样界面才能在「不修改密钥」与「删掉密钥」之间做出区分。</p>
 */
public record PatchProviderRequest(
        String name,
        ProviderType type,
        String baseUrl,
        String apiKey,
        Boolean enabled) {
}
