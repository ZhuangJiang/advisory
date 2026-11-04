package com.advisory.knowledge.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索 SQL（裁决 R1：直接用 {@link JdbcTemplate} 操作 pgvector 与 pg_trgm 运算符，不经过 Spring AI 的
 * {@code VectorStore}）。
 *
 * <p>两条路径共用三个过滤条件：</p>
 * <ul>
 *   <li>{@code JOIN documents d ON d.id = c.document_id} 且 {@code d.status = 'READY'}——一个条件同时
 *       排除了软删除（{@code DELETED}）与所有未完成状态（裁决 R3）；</li>
 *   <li>{@code c.kb_id = ?} 把检索限定在单个知识库内（调用方按库分别跑两条路径，之后统一融合）；</li>
 *   <li>可选的节点范围条件（裁决 R4）：{@code c.node_id IN (范围节点的子树)}，基于 ltree 用
 *       {@code path <@ ANY} 表达。子查询会自然丢弃不存在的 id，悬空 id 因此只是匹配不到而不是报错；
 *       当片段自身的行上设置了范围时，{@code node_id IS NULL} 的片段也会被排除，因为
 *       {@code NULL IN (...)} 并不为真。</li>
 * </ul>
 */
@Repository
public class RetrievalJdbcRepository {

    /**
     * 非空范围下的子树过滤：保留节点位于任一范围节点之下（含自身）的片段。
     *
     * <p>{@code ARRAY(SELECT ...)} 在所有 id 都悬空时给出空数组，而 {@code path <@ ANY(空数组)} 为假，
     * 因此不必额外处理空数组带来的 NULL 陷阱。</p>
     */
    private static final String SCOPE_CLAUSE =
            " AND c.node_id IN ("
                    + "SELECT d.id FROM kb_nodes d"
                    + " WHERE d.path <@ ANY (ARRAY("
                    + "SELECT s.path FROM kb_nodes s WHERE s.id IN (%s))))";

    private static final String COLUMNS =
            "c.id, c.document_id, c.kb_id, c.node_id, d.filename AS doc_name, "
                    + "n.name AS node_name, c.content";

    /** 以余弦相似度作为得分；距离升序即相似度降序。 */
    private static final String VECTOR_SQL = "SELECT " + COLUMNS
            + ", 1 - (c.embedding <=> ?::vector) AS score"
            + " FROM chunks c"
            + " JOIN documents d ON d.id = c.document_id"
            + " LEFT JOIN kb_nodes n ON n.id = c.node_id"
            + " WHERE d.status = 'READY' AND c.kb_id = ? AND c.embedding IS NOT NULL"
            + "%s"
            + " ORDER BY c.embedding <=> ?::vector, c.id"
            + " LIMIT ?";

    /** pg_trgm 关键词路径（裁决 R9）：严格阈值，相似度降序，同分时按 id 稳定排序。 */
    private static final String KEYWORD_SQL = "SELECT " + COLUMNS
            + ", similarity(c.content, ?) AS score"
            + " FROM chunks c"
            + " JOIN documents d ON d.id = c.document_id"
            + " LEFT JOIN kb_nodes n ON n.id = c.node_id"
            + " WHERE d.status = 'READY' AND c.kb_id = ?"
            + "%s"
            + " AND similarity(c.content, ?) > 0.05"
            + " ORDER BY similarity(c.content, ?) DESC, c.id"
            + " LIMIT ?";

    private static final RowMapper<RetrievedChunk> ROW_MAPPER = (rs, rowNum) -> new RetrievedChunk(
            rs.getLong("id"),
            rs.getLong("document_id"),
            rs.getLong("kb_id"),
            (Long) rs.getObject("node_id"),
            rs.getString("doc_name"),
            rs.getString("node_name"),
            rs.getString("content"),
            rs.getDouble("score"));

    /** 驱动一次检索所需的知识库元数据（裁决 R2、R6）。 */
    public record KbMeta(Long embeddingModelId, String reindexStatus) {
    }

    private final JdbcTemplate jdbc;

    public RetrievalJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批量读取知识库元数据；表中不存在的 id 不会出现在返回结果里。
     *
     * @param kbIds 知识库 id
     * @return id 到元数据的映射
     */
    public Map<Long, KbMeta> findKbMeta(List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(kbIds.size(), "?"));
        String sql = "SELECT id, embedding_model_id, reindex_status FROM knowledge_bases"
                + " WHERE id IN (" + placeholders + ")";
        // 这里用 ResultSetExtractor（返回值兼容的 lambda，返回这个 map）自己驱动 rs.next()；
        // 若换成 RowCallbackHandler，它本身已按行回调，再在里面调用 next() 会直接跳过行。
        return jdbc.query(sql, rs -> {
            Map<Long, KbMeta> result = new LinkedHashMap<>();
            while (rs.next()) {
                result.put(rs.getLong("id"),
                        new KbMeta((Long) rs.getObject("embedding_model_id"),
                                rs.getString("reindex_status")));
            }
            return result;
        }, kbIds.toArray());
    }

    /**
     * 向量路径：按余弦距离（{@code <=>}）取最近邻，得分记为 {@code 1 - 距离}。
     *
     * <p>查询向量以 pgvector 文本形式传入并显式 {@code ?::vector} 转型（裁决 R8）；没有向量的行直接跳过。</p>
     */
    public List<RetrievedChunk> vectorTopK(long kbId, String vectorText, List<Long> scopeNodeIds,
                                           int limit) {
        StringBuilder scope = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(vectorText);
        args.add(kbId);
        appendScope(scope, args, scopeNodeIds);
        args.add(vectorText);
        args.add(limit);
        return jdbc.query(applyScope(VECTOR_SQL, scope), ROW_MAPPER, args.toArray());
    }

    /**
     * 关键词路径：对 {@code chunks.content} 计算 {@code pg_trgm} 相似度，阈值严格取 {@code > 0.05}
     * （裁决 R9——中文以字符三元组方式匹配，因此刻意不做 {@code to_tsvector} 的变体）。
     */
    public List<RetrievedChunk> keywordTopK(long kbId, String queryText, List<Long> scopeNodeIds,
                                            int limit) {
        StringBuilder scope = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(queryText);
        args.add(kbId);
        appendScope(scope, args, scopeNodeIds);
        args.add(queryText);
        args.add(queryText);
        args.add(limit);
        return jdbc.query(applyScope(KEYWORD_SQL, scope), ROW_MAPPER, args.toArray());
    }

    /**
     * 构造范围条件，并把它的 id 参数紧跟在 {@code c.kb_id = ?} 之后加入参数列表。
     *
     * <p>参数顺序必须与 SQL 中占位符的顺序严格一致，因此这里顺带负责追加参数，而不是让调用方自己拼。</p>
     */
    private void appendScope(StringBuilder scope, List<Object> args, List<Long> scopeNodeIds) {
        if (scopeNodeIds == null || scopeNodeIds.isEmpty()) {
            return; // 范围为空的参数不追加：等值于整库检索（裁决 R4）
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(scopeNodeIds.size(), "?"));
        scope.append(String.format(SCOPE_CLAUSE, placeholders));
        args.addAll(scopeNodeIds);
    }

    private static String applyScope(String sql, StringBuilder scope) {
        // 总是格式化：范围为空的只是让 %s 占位符消失，即整库检索（裁决 R4）。
        return sql.formatted(scope.toString());
    }
}
