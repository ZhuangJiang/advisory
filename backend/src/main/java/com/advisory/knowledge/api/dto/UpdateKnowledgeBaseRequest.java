package com.advisory.knowledge.api.dto;

/**
 * Partial update for {@code PATCH /api/knowledge-bases/{id}}. Every field is optional; {@code null}
 * means "leave unchanged".
 */
public record UpdateKnowledgeBaseRequest(
        String name,
        String description,
        Long embeddingModelId,
        String chunkConfig) {
}
