package com.advisory.knowledge.api.dto;

/**
 * Presigned upload target. {@code uploadUrl} must be used as-is (no extra signed headers) and
 * {@code objectKey} is sent back unchanged in {@link CreateDocumentRequest}.
 */
public record PresignResponse(String uploadUrl, String objectKey, long expiresInSeconds) {
}
