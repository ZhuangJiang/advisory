package com.advisory.knowledge.api.dto;

import com.advisory.knowledge.domain.KnowledgeBase;

import java.time.Instant;

/**
 * 知识库的返回体。
 *
 * <p>{@code documentCount} 是当前未删除的文档数（列表查询时一并算出；刚创建的知识库恒为 0）。
 * {@code chunkConfig} 原样返回 {@code jsonb} 列里的 JSON 字符串，由前端自行解析。</p>
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
