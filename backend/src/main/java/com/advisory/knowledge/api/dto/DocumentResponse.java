package com.advisory.knowledge.api.dto;

import com.advisory.knowledge.domain.Document;

import java.time.Instant;

/**
 * Document representation returned by upload, read, retry and list endpoints.
 *
 * <p>{@code status} and {@code errorMsg} are what the frontend polls while ingestion is running.</p>
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
