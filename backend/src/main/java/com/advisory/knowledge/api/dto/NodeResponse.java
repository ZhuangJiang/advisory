package com.advisory.knowledge.api.dto;

import com.advisory.knowledge.domain.KbNodeRow;

/**
 * Flat node payload. The frontend builds the tree from {@code parentId}; {@code path} is included
 * for diagnostics and ordering. Built from a fresh JDBC read-back ({@link KbNodeRow}) so it always
 * reflects the committed row after a rename or move.
 */
public record NodeResponse(Long id, Long kbId, Long parentId, String name, String path, int sortOrder) {

    public static NodeResponse from(KbNodeRow node) {
        return new NodeResponse(
                node.id(),
                node.kbId(),
                node.parentId(),
                node.name(),
                node.path(),
                node.sortOrder());
    }
}
