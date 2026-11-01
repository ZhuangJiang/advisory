package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Step 3 of the upload flow: register the object that the client PUT directly to MinIO.
 *
 * <p>Idempotent on {@code objectKey}: replaying the same call returns the existing document.</p>
 */
public record CreateDocumentRequest(
        @NotNull(message = "must not be null")
        Long kbId,

        Long nodeId,

        @NotBlank(message = "must not be blank")
        @Size(max = 512, message = "must not exceed 512 characters")
        String filename,

        @NotBlank(message = "must not be blank")
        @Size(max = 1024, message = "must not exceed 1024 characters")
        String objectKey,

        @PositiveOrZero(message = "must be >= 0")
        Long size,

        @Size(max = 128, message = "must not exceed 128 characters")
        String checksum) {
}
