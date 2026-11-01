package com.advisory.knowledge.domain;

/**
 * Read model for a {@code kb_nodes} row, produced by {@code KbNodeJdbcRepository}.
 *
 * <p>Node reads go through JDBC rather than through the {@link KbNode} entity: the tree is written
 * with JDBC ({@code path} needs explicit {@code ltree} casts), and mixing that with JPA reads in the
 * same persistence context returns stale entities after a write. A plain JDBC projection is always
 * the current database state.</p>
 *
 * @param path the ltree path rendered as text ({@code n1.n5})
 */
public record KbNodeRow(Long id, Long kbId, Long parentId, String name, String path, int sortOrder) {
}
