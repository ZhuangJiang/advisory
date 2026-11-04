package com.advisory.knowledge.domain;

/**
 * {@code kb_nodes} 行的读模型，由 {@code KbNodeJdbcRepository} 产出。
 *
 * <p>节点读取走 JDBC 而不是 {@link KbNode} 实体：树的写入本身就依赖 JDBC（{@code path} 需要显式
 * {@code ltree} 转换），若同一持久化上下文里混用 JPA 读取，写入之后拿到的会是旧实体。
 * 纯 JDBC 投影则永远反映数据库当前状态。</p>
 *
 * @param path ltree 路径的文本形式（{@code n1.n5}）
 */
public record KbNodeRow(Long id, Long kbId, Long parentId, String name, String path, int sortOrder) {
}
