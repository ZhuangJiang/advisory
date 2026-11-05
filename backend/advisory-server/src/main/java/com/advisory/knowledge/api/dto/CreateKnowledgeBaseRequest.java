package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/knowledge-bases} 的请求体。
 *
 * <p>{@code chunkConfig} 可省略，默认 {@code "{}"}；它按 JSON 字符串透传，直接写入 {@code jsonb} 列。</p>
 *
 * @param name             知识库名称
 * @param description      描述，可为空
 * @param embeddingModelId 向量模型 id，可先留空，之后再指定
 * @param chunkConfig      切片参数 JSON，省略时取默认值
 */
public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "名称不能为空") String name,
        String description,
        Long embeddingModelId,
        String chunkConfig) {
}
