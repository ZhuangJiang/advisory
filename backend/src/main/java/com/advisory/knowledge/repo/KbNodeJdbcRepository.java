package com.advisory.knowledge.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;

/**
 * JDBC access for the ltree-specific parts of {@code kb_nodes} that JPA cannot express cleanly.
 *
 * <p>The path label scheme is {@code n{id}} and a node's path is derived from its id and its
 * parent's path, so a row is inserted with a placeholder path and then updated once the generated
 * id is known. Subtree moves are a single {@code UPDATE} using ltree's {@code subpath}/{@code
 * nlevel}/{@code <@} operators.</p>
 */
@Repository
public class KbNodeJdbcRepository {

    private final JdbcTemplate jdbc;

    public KbNodeJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a node and sets its real ltree path. Returns the generated id. For a child,
     * {@code parentPath} must be the parent's current path.
     */
    public long insert(Long kbId, Long parentId, String parentPath, String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // 'placeholder' is an unknown-type literal that PostgreSQL coerces to ltree; binding a
            // String here would arrive as varchar and fail the implicit cast.
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO kb_nodes (kb_id, parent_id, name, path, sort_order) VALUES (?, ?, ?, 'placeholder', 0)",
                    new String[] {"id"});
            ps.setLong(1, kbId);
            if (parentId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, parentId);
            }
            ps.setString(3, name);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert did not return a generated key");
        }
        long id = key.longValue();

        if (parentId == null) {
            jdbc.update("UPDATE kb_nodes SET path = CAST('n' || ? AS ltree) WHERE id = ?", id, id);
        } else {
            jdbc.update("UPDATE kb_nodes SET path = CAST(? || '.n' || ? AS ltree) WHERE id = ?",
                    parentPath, id, id);
        }
        return id;
    }

    /**
     * Rewrites the whole subtree of {@code oldNodePath} so that the moved node lives at
     * {@code newNodePath} and every descendant keeps its relative suffix. {@code newNodePath} is the
     * new full path of the moved node itself (e.g. {@code n5} or {@code n1.n5}).
     *
     * <p>The moved node itself is handled by the {@code CASE} branch because ltree's
     * {@code subpath(path, nlevel(...))} raises "invalid positions" when {@code start == nlevel};
     * descendants always have {@code nlevel > nlevel(oldNodePath)} so {@code subpath} is valid there.</p>
     */
    public void rewriteSubtreePath(String newNodePath, String oldNodePath) {
        jdbc.update("""
                UPDATE kb_nodes
                SET path = CASE
                    WHEN path = CAST(? AS ltree) THEN CAST(? AS ltree)
                    ELSE CAST(? AS ltree) || subpath(path, nlevel(CAST(? AS ltree)))
                END
                WHERE path <@ CAST(? AS ltree)
                """, oldNodePath, newNodePath, newNodePath, oldNodePath, oldNodePath);
    }

    /**
     * Returns true when {@code descendantPath} is {@code ancestorPath} or lives inside it
     * (equivalent to {@code descendantPath <@ ancestorPath} in ltree).
     */
    public boolean isDescendantOrSelf(String descendantPath, String ancestorPath) {
        Boolean result = jdbc.queryForObject(
                "SELECT CAST(? AS ltree) <@ CAST(? AS ltree)", Boolean.class, descendantPath, ancestorPath);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Returns true when a sibling with the given (trimmed) name already exists under the same parent
     * in the knowledge base. {@code excludeId} is used to ignore the node itself on rename. Uses
     * {@code IS NOT DISTINCT FROM} so a {@code NULL} parent only matches the partial root index.
     */
    public boolean existsSibling(Long kbId, Long parentId, String name, Long excludeId) {
        String sql = "SELECT count(*) FROM kb_nodes WHERE kb_id = ? AND parent_id IS NOT DISTINCT FROM ? AND name = ?";
        Integer count;
        if (excludeId == null) {
            count = jdbc.queryForObject(sql, Integer.class, kbId, parentId, name);
        } else {
            count = jdbc.queryForObject(sql + " AND id <> ?", Integer.class, kbId, parentId, name, excludeId);
        }
        return count != null && count > 0;
    }

    /** Renames a node by id. Unique-index violations surface as {@code DataIntegrityViolationException}. */
    public void updateName(long id, String name) {
        jdbc.update("UPDATE kb_nodes SET name = ? WHERE id = ?", name, id);
    }

    /** Counts non-deleted documents attached to the node (used to reject deletion). */
    public long countDocuments(long nodeId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE node_id = ? AND status <> 'DELETED'", Long.class, nodeId);
        return count == null ? 0 : count;
    }
}
