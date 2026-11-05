package com.advisory.knowledge.api.dto;

/**
 * {@code GET /api/documents/{id}/chunks} 返回的单条片段。
 *
 * <p>刻意不包含向量：每个片段 1024 个浮点数对界面毫无用处，却会占掉绝大部分响应体积。
 * {@code headingPath} 是切片器产出的章节路径（{@code "A > B > C"}），没有标题的文档为空串（由 V3 迁移落库）。</p>
 */
public record ChunkResponse(
        Long id,
        int seq,
        String headingPath,
        String content,
        Integer tokenCount) {
}
