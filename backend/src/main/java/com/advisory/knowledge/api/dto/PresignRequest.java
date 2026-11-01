package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Step 1 of the upload flow: ask for a presigned PUT URL.
 *
 * <p>{@code nodeId} is optional (a document may hang off the knowledge base root); {@code size} is
 * optional as well but, when present, is validated against the 50 MB limit.</p>
 */
public record PresignRequest(
        @NotNull(message = "must not be null")
        Long kbId,

        Long nodeId,

        @NotBlank(message = "must not be blank")
        @Size(max = 512, message = "must not exceed 512 characters")
        String filename,

        @PositiveOrZero(message = "must be >= 0")
        Long size) {
}
