package com.advisory.knowledge.api.dto;

import com.advisory.knowledge.domain.KbNode;

/**
 * Flat node payload. The frontend builds the tree from {@code parentId}; {@code path} is included
 * for diagnostics and ordering.
 */
public record NodeResponse(Long id, Long kbId, Long parentId, String name, String path, int sortOrder) {

    public static NodeResponse from(KbNode node) {
        return new NodeResponse(
                node.getId(),
                node.getKbId(),
                node.getParentId(),
                node.getName(),
                node.getPath(),
                node.getSortOrder() == null ? 0 : node.getSortOrder());
    }
}
