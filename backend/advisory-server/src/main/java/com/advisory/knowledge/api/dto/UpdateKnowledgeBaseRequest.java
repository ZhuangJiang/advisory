package com.advisory.knowledge.api.dto;

/**
 * {@code PATCH /api/knowledge-bases/{id}} 的局部更新请求：所有字段可选，{@code null} 表示保持不变。
 */
public record UpdateKnowledgeBaseRequest(
        String name,
        String description,
        Long embeddingModelId,
        String chunkConfig) {
}
