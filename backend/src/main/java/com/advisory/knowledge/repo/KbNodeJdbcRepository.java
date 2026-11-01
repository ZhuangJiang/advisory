package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.KbNodeRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access for the ltree-specific parts of {@code kb_nodes} that JPA cannot express cleanly.
 *
 * <p>The path label scheme is {@code n{id}} and a node's path is derived from its id and its
 * parent's path, so a row is inserted with a placeholder path and then updated once the generated
 * id is known. Subtree moves are a single {@code UPDATE} using ltree's {@code subpath}/{@code
 * nlevel}/{@code <@} operators; that statement also rewrites {@code parent_id} for the moved node so
 * the column, the unique {@code (kb_id, parent_id, name)} index and the {@code path} stay
 * consistent.</p>
 *
 * <p>Reads also live here (as {@link KbNodeRow} projections): the tree is mutated with JDBC, and a
 * JPA {@code findById} after such a write would return the stale entity from the persistence
 * context instead of the row that is actually stored.</p>
 */
@Repository
public class KbNodeJdbcRepository {

    private static final String COLUMNS =
            "id, kb_id, parent_id, name, CAST(path AS text) AS path, sort_order";

    private static final RowMapper<KbNodeRow> ROW_MAPPER = (rs, rowNum) -> new KbNodeRow(
            rs.getLong("id"),
            rs.getLong("kb_id"),
            rs.getObject("parent_id", Long.class),
            rs.getString("name"),
            rs.getString("path"),
            rs.getInt("sort_order"));

    private final JdbcTemplate jdbc;

    public KbNodeJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads a single node straight from the database, bypassing any JPA persistence context. */
    public Optional<KbNodeRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM kb_nodes WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    /** Lists the nodes of a knowledge base ordered by ltree path (parents before children). */
    public List<KbNodeRow> findByKbId(long kbId) {
        // Qualify the ORDER BY: the output column "path" is text, the table column is ltree.
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_nodes WHERE kb_id = ? ORDER BY kb_nodes.path",
                ROW_MAPPER, kbId);
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
     * Moves the subtree rooted at {@code oldNodePath} to {@code newNodePath} and reparents the moved
     * node to {@code newParentId} ({@code null} = root) in one atomic statement. Descendants keep
     * their relative suffix; their {@code parent_id} is untouched because only the moved node
     * changes parent.
     *
     * <p>The moved node itself is handled by the {@code CASE} branch because ltree's
     * {@code subpath(path, nlevel(...))} raises "invalid positions" when {@code start == nlevel};
     * descendants always have {@code nlevel > nlevel(oldNodePath)} so {@code subpath} is valid there.
     * A {@code null} parent is bound as SQL {@code NULL} ({@link Types#BIGINT}), never as {@code 0}.</p>
     *
     * @return the number of rows updated (the subtree size)
     */
    public int moveSubtree(long nodeId, String oldNodePath, String newNodePath, Long newParentId) {
        return jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    UPDATE kb_nodes
                    SET path = CASE
                            WHEN id = ? THEN CAST(? AS ltree)
                            ELSE CAST(? AS ltree) || subpath(path, nlevel(CAST(? AS ltree)))
                        END,
                        parent_id = CASE WHEN id = ? THEN ? ELSE parent_id END
                    WHERE path <@ CAST(? AS ltree)
                    """);
            ps.setLong(1, nodeId);
            ps.setString(2, newNodePath);
            ps.setString(3, newNodePath);
            ps.setString(4, oldNodePath);
            ps.setLong(5, nodeId);
            if (newParentId == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, newParentId);
            }
            ps.setString(7, oldNodePath);
            return ps;
        });
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
     * in the knowledge base. {@code excludeId} is used to ignore the node itself on rename/move.
     *
     * <p>Two variants are used so the {@code (kb_id, parent_id, name)} index can serve the common
     * non-null-parent case; only the root case needs the partial {@code ux_kb_nodes_root_name}
     * index via {@code parent_id IS NULL}.</p>
     */
    public boolean existsSibling(Long kbId, Long parentId, String name, Long excludeId) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM kb_nodes WHERE kb_id = ? AND ");
        if (parentId == null) {
            sql.append("parent_id IS NULL AND name = ?");
        } else {
            sql.append("parent_id = ? AND name = ?");
        }
        if (excludeId != null) {
            sql.append(" AND id <> ?");
        }

        Integer count = parentId == null
                ? (excludeId == null
                        ? jdbc.queryForObject(sql.toString(), Integer.class, kbId, name)
                        : jdbc.queryForObject(sql.toString(), Integer.class, kbId, name, excludeId))
                : (excludeId == null
                        ? jdbc.queryForObject(sql.toString(), Integer.class, kbId, parentId, name)
                        : jdbc.queryForObject(sql.toString(), Integer.class, kbId, parentId, name, excludeId));
        return count != null && count > 0;
    }

    /** Counts the direct children of a node (used to reject deletion of a non-leaf). */
    public long countByParentId(long parentId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_nodes WHERE parent_id = ?", Long.class, parentId);
        return count == null ? 0 : count;
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
