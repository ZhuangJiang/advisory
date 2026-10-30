package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/knowledge-bases/{kbId}/nodes}. {@code parentId} is null for a
 * root node.
 */
public record CreateNodeRequest(
        Long parentId,
        @NotBlank(message = "name must not be blank") String name) {
}
