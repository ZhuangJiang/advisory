package com.advisory.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/knowledge-bases/{kbId}/nodes} 的请求体。
 *
 * @param parentId 父节点 id，{@code null} 表示创建根节点
 * @param name     节点名称，同一父节点下不可重名
 */
public record CreateNodeRequest(
        Long parentId,
        @NotBlank(message = "name must not be blank") String name) {
}
