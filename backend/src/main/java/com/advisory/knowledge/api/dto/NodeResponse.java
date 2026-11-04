package com.advisory.knowledge.api.dto;

import com.advisory.knowledge.domain.KbNodeRow;

/**
 * 扁平化的节点返回体。
 *
 * <p>前端依据 {@code parentId} 自行组装成树；{@code path} 附带返回，便于排查问题与稳定排序。
 * 数据来自一次全新的 JDBC 回读（{@link KbNodeRow}），因此重命名或移动之后返回的一定是已提交的那一行。</p>
 *
 * @param id        节点主键
 * @param kbId      所属知识库
 * @param parentId  父节点，根节点为 {@code null}
 * @param name      节点名称
 * @param path      ltree 路径的文本形式，如 {@code n1.n5}
 * @param sortOrder 同层排序序号
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
