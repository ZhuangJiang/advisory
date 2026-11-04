package com.advisory.knowledge.api.dto;

import com.advisory.knowledge.domain.Document;

import java.time.Instant;

/**
 * 文档在上传、查询、重试与列表接口中的统一表示。
 *
 * <p>前端在入库期间轮询的正是 {@code status} 与 {@code errorMsg} 这两个字段。</p>
 */
public record DocumentResponse(
        Long id,
        Long kbId,
        Long nodeId,
        String filename,
        String objectKey,
        String mime,
        long size,
        String checksum,
        String status,
        String errorMsg,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getKbId(),
                document.getNodeId(),
                document.getFilename(),
                document.getObjectKey(),
                document.getMime(),
                document.getSize() == null ? 0 : document.getSize(),
                document.getChecksum(),
                document.getStatus(),
                document.getErrorMsg(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
