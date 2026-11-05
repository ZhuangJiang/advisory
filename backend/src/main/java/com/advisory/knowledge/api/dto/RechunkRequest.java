package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量重新裁切的请求：只带文档主键，切片参数取知识库当前的 {@code chunk_config}。
 *
 * <p>一次最多 200 篇——再多就该用「重建索引」，那是按知识库维度设计的入口。</p>
 */
public record RechunkRequest(
        @NotEmpty(message = "不能为空")
        @Size(max = 200, message = "一次最多提交 200 篇文档")
        List<@NotNull(message = "不能为空") Long> documentIds) {
}
