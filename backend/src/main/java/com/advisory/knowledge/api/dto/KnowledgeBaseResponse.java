package com.advisory.knowledge.api.dto;

import com.advisory.knowledge.domain.KnowledgeBase;

import java.time.Instant;

/**
 * Knowledge base payload. {@code documentCount} is the number of non-deleted documents currently in
 * the knowledge base (resolved during listing; always 0 for freshly created knowledge bases).
 * {@code chunkConfig} is returned as the raw JSON string of the {@code jsonb} column.
 */
public record KnowledgeBaseResponse(
        Long id,
        String name,
        String description,
        Long embeddingModelId,
        String chunkConfig,
        String reindexStatus,
        Instant createdAt,
        long documentCount) {

    public static KnowledgeBaseResponse from(KnowledgeBase kb) {
        return from(kb, 0);
    }

    public static KnowledgeBaseResponse from(KnowledgeBase kb, long documentCount) {
        return new KnowledgeBaseResponse(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getEmbeddingModelId(),
                kb.getChunkConfig(),
                kb.getReindexStatus(),
                kb.getCreatedAt(),
                documentCount);
    }
}
