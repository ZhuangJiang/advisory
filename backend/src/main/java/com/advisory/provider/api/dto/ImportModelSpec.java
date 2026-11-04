package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ModelCapability;

/**
 * 导入请求中的一项：把某个远端模型复制进本地 {@code models} 表，并指定其用途
 * （向量模型还要指定维度）。
 *
 * @param modelName  远端模型名
 * @param capability 用途，由运维指定
 * @param dimension  向量维度，仅 {@code EMBEDDING} 需要
 */
public record ImportModelSpec(
        String modelName,
        ModelCapability capability,
        Integer dimension) {
}
