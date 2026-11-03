package com.advisory.knowledge.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The retrieval SQL (ruling R1: plain {@link JdbcTemplate} with explicit pgvector / pg_trgm
 * operators — no Spring AI {@code VectorStore}).
 *
 * <p>Both paths share three filters:</p>
 * <ul>
 *   <li>{@code JOIN documents d ON d.id = c.document_id} with {@code d.status = 'READY'} — one
 *       condition excludes soft-deleted ({@code DELETED}) and every non-terminal document state
 *       (ruling R3);</li>
 *   <li>{@code c.kb_id = ?} bounds the search to one knowledge base (the caller runs both paths
 *       per KB and fuses afterwards);</li>
 *   <li>an optional node-scope predicate (ruling R4): {@code c.node_id IN (subtree of scope ids)}
 *       built over ltree — {@code path <@ ANY} of the scope nodes' paths. The inner lookup drops
 *       unknown ids naturally, so dangling ids match nothing instead of erroring; a scope set on
 *       the chunk's own row also excludes {@code node_id IS NULL} chunks because {@code NULL IN
 *       (...)} is not true.</li>
 * </ul>
 */
@Repository
public class RetrievalJdbcRepository {

    /**
     * Subtree filter for a non-empty scope: keep chunks whose node lies at or below any of the
     * scope nodes. {@code ARRAY(SELECT ...)} yields an empty array when every id is dangling,
     * and {@code path <@ ANY(empty)} is false — no NULL-array pitfalls.
     */
    private static final String SCOPE_CLAUSE =
            " AND c.node_id IN ("
                    + "SELECT d.id FROM kb_nodes d"
                    + " WHERE d.path <@ ANY (ARRAY("
                    + "SELECT s.path FROM kb_nodes s WHERE s.id IN (%s))))";

    private static final String COLUMNS =
            "c.id, c.document_id, c.kb_id, c.node_id, d.filename AS doc_name, "
                    + "n.name AS node_name, c.content";

    /** Cosine similarity as the score; distance ascending is similarity descending. */
    private static final String VECTOR_SQL = "SELECT " + COLUMNS
            + ", 1 - (c.embedding <=> ?::vector) AS score"
            + " FROM chunks c"
            + " JOIN documents d ON d.id = c.document_id"
            + " LEFT JOIN kb_nodes n ON n.id = c.node_id"
            + " WHERE d.status = 'READY' AND c.kb_id = ? AND c.embedding IS NOT NULL"
            + "%s"
            + " ORDER BY c.embedding <=> ?::vector, c.id"
            + " LIMIT ?";

    /** pg_trgm keyword path (ruling R9): strict threshold, similarity descending, id tie-break. */
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

    /** Knowledge base metadata needed to drive one retrieval (R2/R6). */
    public record KbMeta(Long embeddingModelId, String reindexStatus) {
    }

    private final JdbcTemplate jdbc;

    public RetrievalJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns metadata for the requested ids; ids absent from the table are absent here. */
    public Map<Long, KbMeta> findKbMeta(List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(kbIds.size(), "?"));
        String sql = "SELECT id, embedding_model_id, reindex_status FROM knowledge_bases"
                + " WHERE id IN (" + placeholders + ")";
        // ResultSetExtractor (value-compatible lambda: it returns the map) drives rs.next() itself —
        // a RowCallbackHandler would fire once per row, so re-calling next() inside it skips rows.
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
     * Vector path: nearest neighbours by cosine distance ({@code <=>}), scored as
     * {@code 1 - distance}. The query vector is passed as pgvector text with {@code ?::vector}
     * (ruling R8); rows without an embedding are skipped.
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
     * Keyword path: {@code pg_trgm} similarity over {@code chunks.content} with a strict
     * {@code > 0.05} threshold (ruling R9 — Chinese matches as character trigrams, so there is
     * deliberately no {@code to_tsvector} variant).
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

    /** Builds the scope predicate and appends its ids right after the {@code c.kb_id = ?} arg. */
    private void appendScope(StringBuilder scope, List<Object> args, List<Long> scopeNodeIds) {
        if (scopeNodeIds == null || scopeNodeIds.isEmpty()) {
            return; // empty scope = whole knowledge base (ruling R4)
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(scopeNodeIds.size(), "?"));
        scope.append(String.format(SCOPE_CLAUSE, placeholders));
        args.addAll(scopeNodeIds);
    }

    private static String applyScope(String sql, StringBuilder scope) {
        // Always format: an empty scope simply drops the %s placeholder (whole-KB search, R4).
        return sql.formatted(scope.toString());
    }
}
