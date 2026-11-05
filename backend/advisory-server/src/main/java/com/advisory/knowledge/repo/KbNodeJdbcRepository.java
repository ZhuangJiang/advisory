package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.KbNodeRow;
import com.advisory.common.jdbc.JdbcRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * {@code kb_nodes} 中 JPA 难以表达的那部分（ltree）的 JDBC 访问层。
 *
 * <p>路径标签的规则是 {@code n{id}}，节点路径由自身 id 与父节点路径推导，因此插入时先写占位路径，
 * 拿到自增 id 后再回填真实路径。子树移动用一条 {@code UPDATE} 借助 ltree 的
 * {@code subpath}/{@code nlevel}/{@code <@} 运算符完成；同一条语句还会改写被移动节点的 {@code parent_id}，
 * 保证该列、唯一索引 {@code (kb_id, parent_id, name)} 与 {@code path} 三者始终一致。</p>
 *
 * <p>读取也放在这里（以 {@link KbNodeRow} 投影返回）：树是用 JDBC 改的，改完再用 JPA 的
 * {@code findById} 读到的会是持久化上下文里的旧实体，而不是真正落库的那一行。</p>
 */
@Repository
public class KbNodeJdbcRepository extends JdbcRepository {

    private static final String COLUMNS =
            "id, kb_id, parent_id, name, CAST(path AS text) AS path, sort_order";

    private static final RowMapper<KbNodeRow> ROW_MAPPER = (rs, rowNum) -> new KbNodeRow(
            rs.getLong("id"),
            rs.getLong("kb_id"),
            rs.getObject("parent_id", Long.class),
            rs.getString("name"),
            rs.getString("path"),
            rs.getInt("sort_order"));



    /** 直接从数据库读取单个节点，绕过一切 JPA 持久化上下文。 */
    public Optional<KbNodeRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM kb_nodes WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    /** 列出某知识库的全部节点，按 ltree 路径排序（父节点一定排在子节点之前）。 */
    public List<KbNodeRow> findByKbId(long kbId) {
        // ORDER BY 必须带表名限定：输出列 "path" 是 text，而表列是 ltree，不加限定会排序到文本类型上。
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kb_nodes WHERE kb_id = ? ORDER BY kb_nodes.path",
                ROW_MAPPER, kbId);
    }

    /**
     * 插入节点并回填真实的 ltree 路径。
     *
     * @param kbId       所属知识库
     * @param parentId   父节点，{@code null} 表示根
     * @param parentPath 父节点当前路径；创建根节点时可为 {@code null}
     * @param name       节点名称
     * @param sortOrder  同层排序序号
     * @return 数据库生成的主键
     */
    public long insert(Long kbId, Long parentId, String parentPath, String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // 'placeholder' 是未知类型字面量，PostgreSQL 会把它隐式转换为 ltree；
            // 若在这里绑定一个 String，到达服务端时类型是 varchar，隐式转换会直接失败。
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
            throw new IllegalStateException("插入后没有取到自增主键");
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
     * 用一条原子语句把以 {@code oldNodePath} 为根的子树搬到 {@code newNodePath}，同时把被移动节点的
     * 父节点改为 {@code newParentId}（{@code null} 表示根）。后代节点保留各自的相对后缀，其
     * {@code parent_id} 不用改——父节点发生变化的只有被移动的那一个。
     *
     * <p>被移动节点自身必须走 {@code CASE} 分支：ltree 的 {@code subpath(path, nlevel(...))} 在
     * {@code start == nlevel} 时会直接抛「invalid positions」；而后代的 {@code nlevel} 恒大于
     * {@code nlevel(oldNodePath)}，{@code subpath} 对它们总是合法。
     * 空父节点要绑定为 SQL {@code NULL}（{@link Types#BIGINT}），绝不能绑成 {@code 0}——
     * 那会指向一个不存在的父节点。</p>
     *
     * @param oldNodePath  原子树的根路径
     * @param newNodePath  移动后的新路径
     * @param newParentId  新父节点，{@code null} 表示根
     * @param kbId         知识库，限定更新范围
     * @return 更新的行数，即子树大小
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
     * 判断 {@code descendantPath} 是否就是 {@code ancestorPath} 本身或位于其子树内
     * （等价于 ltree 的 {@code descendantPath <@ ancestorPath}）。
     *
     * @param descendantPath 待判断的路径
     * @param ancestorPath   祖先路径
     * @return 在子树内（含自身）返回 {@code true}
     */
    public boolean isDescendantOrSelf(String descendantPath, String ancestorPath) {
        Boolean result = jdbc.queryForObject(
                "SELECT CAST(? AS ltree) <@ CAST(? AS ltree)", Boolean.class, descendantPath, ancestorPath);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 判断同一父节点下是否已存在同名（已去空格）的兄弟节点。
     *
     * <p>{@code excludeId} 用于重命名或移动时排除节点自身，否则它总会和自己重名。</p>
     *
     * <p>拆成两种查询是为了让 {@code (kb_id, parent_id, name)} 索引能服务「父节点非空」这个常见情形；
     * 只有根节点这一种需要走 {@code parent_id IS NULL} 的部分索引 {@code ux_kb_nodes_root_name}。</p>
     *
     * @param kbId      知识库
     * @param parentId  父节点，{@code null} 表示根层
     * @param name      待检查的名称
     * @param excludeId 需要排除的节点 id，可为 {@code null}
     * @return 已存在同名兄弟节点返回 {@code true}
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

    /** 统计节点的直接子节点数，用于拒绝删除非叶子节点。 */
    public long countByParentId(long parentId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_nodes WHERE parent_id = ?", Long.class, parentId);
        return count == null ? 0 : count;
    }

    /** 按 id 重命名节点；触发唯一索引冲突时会抛 {@code DataIntegrityViolationException}，由上层翻译。 */
    public void updateName(long id, String name) {
        jdbc.update("UPDATE kb_nodes SET name = ? WHERE id = ?", name, id);
    }

    /** 统计挂在该节点上的未删除文档数，用于拒绝删除仍有文档的节点。 */
    public long countDocuments(long nodeId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE node_id = ? AND status <> 'DELETED'", Long.class, nodeId);
        return count == null ? 0 : count;
    }
}
