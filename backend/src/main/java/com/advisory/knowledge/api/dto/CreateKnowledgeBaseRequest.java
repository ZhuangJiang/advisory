package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/knowledge-bases}. {@code chunkConfig} is optional and defaults to
 * {@code "{}"}; it is passed through as a JSON string and stored in the {@code jsonb} column.
 */
public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "name must not be blank") String name,
        String description,
        Long embeddingModelId,
        String chunkConfig) {
}
