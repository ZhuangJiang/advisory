package com.advisory.knowledge.api.dto;

import com.advisory.knowledge.domain.Document;

import java.time.Instant;

/**
 * 文档在上传、查询、重试与列表接口中的统一表示。
 *
 * <p>前端在入库期间轮询的正是 {@code status} 与 {@code errorMsg} 这两个字段。
 * {@code queuePosition} 只在 {@code PENDING} 时有值，表示「前面还有几个文档在裁切队列里排着」，
 * 让用户看到批量上传确实在按顺序处理，而不是卡住了。其它状态为 {@code null}，序列化时直接省略。</p>
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
        Instant updatedAt,
        Integer queuePosition) {

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
                document.getUpdatedAt(),
                null);
    }

    /**
     * 复制一份并带上排队位次。
     *
     * @param position 从 1 开始的位次；不在排队中传 {@code null}
     * @return 带位次的响应对象
     */
    public DocumentResponse withQueuePosition(Integer position) {
        return new DocumentResponse(id, kbId, nodeId, filename, objectKey, mime, size, checksum,
                status, errorMsg, createdAt, updatedAt, position);
    }
}
