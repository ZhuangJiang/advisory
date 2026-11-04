package com.advisory.knowledge;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import com.advisory.knowledge.retrieval.RetrievalQuery;
import com.advisory.knowledge.retrieval.RetrievalService;
import com.advisory.knowledge.retrieval.RetrievedChunk;
import com.advisory.knowledge.retrieval.RrfFusion;
import com.advisory.provider.service.CryptoService;
import com.advisory.support.EmbeddingModelFixture;
import com.advisory.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hybrid retrieval: per-KB vector + pg_trgm keyword paths fused with RRF (k=60), cross-KB
 * {@code nodeScope} filtering through ltree subtrees, document-status filtering via the
 * {@code documents} join, REINDEXING rejection, and the fusion unit contract.
 *
 * <p>Where controlled ranking matters (scope tests, finalTopK, keyword-vs-vector), chunks are
 * seeded directly with {@link JdbcTemplate} so the assertions do not depend on embedding
 * behaviour. The shared deterministic stub {@link EmbeddingGateway} (see
 * {@code TestEmbeddingGatewayConfig}) backs the vector path in
 * {@link #keywordPathFindsExactPhrase()}.</p>
 */
class RetrievalTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private EmbeddingGateway embeddingGateway;

    private EmbeddingModelFixture embeddingFixture;
    private final List<Long> createdKbIds = new ArrayList<>();

    @BeforeEach
    void createFixtures() {
        embeddingFixture = EmbeddingModelFixture.create(jdbc, cryptoService);
    }

    @AfterEach
    void cleanup() {
        // documents and kb_nodes reference the kb; chunks cascade off documents.
        for (Long kbId : createdKbIds) {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        }
        createdKbIds.clear();
        if (embeddingFixture != null) {
            embeddingFixture.close();
            embeddingFixture = null;
        }
    }

    // --------------------------------------------------------- scope / status filtering

    /** Scope is per KB: A restricted to node X must not return A/Y chunks nor any B chunk. */
    @Test
    void respectsPerKbNodeScope() {
        long kbA = createKb("scope-a");
        long kbB = createKb("scope-b");
        long nodeX = createNode(kbA, null);
        long nodeY = createNode(kbA, null);
        long nodeB = createNode(kbB, null);
        long nodeBelsewhere = createNode(kbB, null);
        String content = "跨库范围共享内容scope-token-" + uniqueToken();

        long inX = insertChunk(kbA, nodeX, content);
        long inY = insertChunk(kbA, nodeY, content);
        long noNode = insertChunk(kbA, null, content);
        long inB = insertChunk(kbB, nodeB, content);

        // Both KBs are scoped: A to node X (holds the shared content), B to a different node
        // that holds none of it — so neither A/Y, A/no-node nor B's copy may leak in. The extra
        // entry for a KB outside kbIds must be ignored (R4) without affecting the result.
        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kbA, kbB),
                Map.of(kbA, List.of(nodeX), kbB, List.of(nodeBelsewhere),
                        999999L, List.of(nodeY)),
                List.of(content), 20, 8));

        Set<Long> returnedIds = chunkIdsOf(results);
        assertFalse(returnedIds.contains(inY), "chunks under the out-of-scope node Y must not be returned");
        assertFalse(returnedIds.contains(noNode), "chunks with node_id NULL are excluded once a scope is set");
        assertFalse(returnedIds.contains(inB), "the same content under a different KB must not leak in");
        assertTrue(returnedIds.contains(inX), "the in-scope chunk must be returned, got " + results);
        for (RetrievedChunk chunk : results) {
            assertEquals(kbA, chunk.kbId(), "only knowledge base A may contribute results");
            assertEquals(nodeX, chunk.nodeId(), "only node X may contribute results");
        }
    }

    /** A parent id in scope selects the whole subtree: children and grandchildren included. */
    @Test
    void scopeIncludesDescendants() {
        long kb = createKb("scope-desc");
        long parent = createNode(kb, null);
        long child = createNode(kb, parent);
        long grandchild = createNode(kb, child);
        String content = "子树范围内容desc-token-" + uniqueToken();

        long underChild = insertChunk(kb, child, content);
        long underGrandchild = insertChunk(kb, grandchild, content);

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(kb, List.of(parent)), List.of(content), 20, 8));

        Set<Long> returnedIds = chunkIdsOf(results);
        assertTrue(returnedIds.contains(underChild), "a chunk under the scoped node's child must be returned");
        assertTrue(returnedIds.contains(underGrandchild), "a chunk deeper in the subtree must be returned");
    }

    /** A DELETED document's chunks never appear, even with an exact keyword match. */
    @Test
    void excludesDeletedDocuments() {
        long kb = createKb("excl-deleted");
        long node = createNode(kb, null);
        String content = "软删文档精确匹配deleted-token-" + uniqueToken();

        long readyDoc = insertDocument(kb, node, "READY");
        long deletedDoc = insertDocument(kb, node, "DELETED");
        long readyChunk = insertChunk(kb, node, content, readyDoc);
        insertChunk(kb, node, content, deletedDoc);

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(content), 20, 8));

        Set<Long> returnedIds = chunkIdsOf(results);
        assertTrue(returnedIds.contains(readyChunk), "the READY control chunk must be returned");
        for (RetrievedChunk chunk : results) {
            assertTrue(chunk.documentId() != deletedDoc,
                    "a DELETED document's chunk must never be returned, got " + chunk);
        }
    }

    /** Non-terminal document states (PENDING/FAILED) are filtered out by the same join. */
    @Test
    void excludesNonReadyDocuments() {
        long kb = createKb("excl-nonready");
        long node = createNode(kb, null);
        String content = "非就绪文档过滤nonready-token-" + uniqueToken();

        long readyDoc = insertDocument(kb, node, "READY");
        long pendingDoc = insertDocument(kb, node, "PENDING");
        long failedDoc = insertDocument(kb, node, "FAILED");
        long readyChunk = insertChunk(kb, node, content, readyDoc);
        long pendingChunk = insertChunk(kb, node, content, pendingDoc);
        long failedChunk = insertChunk(kb, node, content, failedDoc);

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(content), 20, 8));

        Set<Long> returnedIds = chunkIdsOf(results);
        assertTrue(returnedIds.contains(readyChunk), "the READY control chunk must be returned");
        assertFalse(returnedIds.contains(pendingChunk), "PENDING document chunks must be filtered out");
        assertFalse(returnedIds.contains(failedChunk), "FAILED document chunks must be filtered out");
    }

    /** Dangling scope ids are ignored: no exception, and nothing matches them. */
    @Test
    void ignoresDanglingScopeNodeIds() {
        long kb = createKb("scope-dangling");
        long node = createNode(kb, null);
        String content = "悬空节点范围dangling-token-" + uniqueToken();
        insertChunk(kb, node, content);

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(kb, List.of(999999L)), List.of(content), 20, 8));

        assertTrue(results.isEmpty(),
                "a scope of only dangling ids must match nothing without throwing, got " + results);
    }

    /** REINDEXING blocks retrieval with 409 KB_REINDEXING (the Task 7 handoff obligation). */
    @Test
    void reindexingKbRejected() {
        long kb = createKb("reindexing-block");
        jdbc.update("UPDATE knowledge_bases SET reindex_status = 'REINDEXING' WHERE id = ?", kb);

        AppException exception = assertThrows(AppException.class, () -> retrievalService.retrieve(
                new RetrievalQuery(List.of(kb), Map.of(), List.of("任意查询"), 20, 8)));

        assertEquals(ErrorCode.KB_REINDEXING, exception.getCode());
        assertEquals(409, exception.getCode().getHttpStatus().value(),
                "KB_REINDEXING must map to HTTP 409");
    }

    /**
     * Every (kbId, path, query) triple must reach {@link RrfFusion#fuse} as its own ranked list:
     * the chunk below ranks 2nd in BOTH query keyword lists, so it sums {@code 2 × 1/62} and
     * beats a rank-1-only chunk's {@code 1/61}. The pre-review merge kept a single best
     * contribution per chunk ({@code 1/62}) and would have ranked it last instead.
     */
    @Test
    void multiQueryContributionsAccumulate() {
        long kb = createKb("multi-query");
        long node = createNode(kb, null);
        // Deliberately disjoint texts: no shared trigrams between q1 and q2, so each query's
        // keyword list holds exactly its own exact hit plus the combined chunk.
        String q1 = "阿尔法财务折旧核算口径xyz1";
        String q2 = "微波通信天线增益测试uvw2";
        long chunkQ1 = insertChunk(kb, node, q1);          // rank 1 in q1's list only
        long chunkQ2 = insertChunk(kb, node, q2);          // rank 1 in q2's list only
        long chunkBoth = insertChunk(kb, node, q1 + "；" + q2); // rank 2 in both lists

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(q1, q2), 20, 8));

        Set<Long> ids = chunkIdsOf(results);
        assertTrue(ids.contains(chunkQ1), "q1's exact hit must appear, got " + results);
        assertTrue(ids.contains(chunkQ2), "q2's exact hit must appear, got " + results);
        assertEquals(3, ids.size(), "only the three seeded chunks are eligible, got " + results);

        RetrievedChunk top = results.get(0);
        assertEquals(chunkBoth, top.chunkId(),
                "the chunk found by both queries must win through accumulated contributions");
        assertEquals(2.0 / 62, top.score(), 1e-12,
                "score must be 1/(60+2) from q1's list + 1/(60+2) from q2's list — one contribution per list");
    }

    /** Only REINDEXING blocks: a FAILED knowledge base must stay retrievable (design §10). */
    @Test
    void failedKbStillRetrievable() {
        long kb = createKb("failed-still-works");
        long node = createNode(kb, null);
        String content = "失败库仍可检索failed-kb-token-" + uniqueToken();
        long chunk = insertChunk(kb, node, content);
        jdbc.update("UPDATE knowledge_bases SET reindex_status = 'FAILED' WHERE id = ?", kb);

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(content), 20, 8));

        assertTrue(chunkIdsOf(results).contains(chunk),
                "a FAILED knowledge base must still serve retrieval, got " + results);
    }

    /** Missing scope key = whole KB: every node and even node-less chunks are eligible. */
    @Test
    void emptyScopeCoversWholeKb() {
        long kb = createKb("scope-empty");
        long node1 = createNode(kb, null);
        long node2 = createNode(kb, null);
        String content = "全库范围内容whole-kb-token-" + uniqueToken();

        long underNode1 = insertChunk(kb, node1, content);
        long underNode2 = insertChunk(kb, node2, content);
        long underNothing = insertChunk(kb, null, content);

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(content), 20, 8));

        Set<Long> returnedIds = chunkIdsOf(results);
        assertTrue(returnedIds.contains(underNode1), "chunks under node1 must be eligible without a scope");
        assertTrue(returnedIds.contains(underNode2), "chunks under node2 must be eligible without a scope");
        assertTrue(returnedIds.contains(underNothing), "node-less chunks are eligible without a scope");
    }

    /** finalTopK caps the fused list exactly. */
    @Test
    void respectsFinalTopK() {
        long kb = createKb("final-topk");
        long node = createNode(kb, null);
        String content = "最终数量截断topk-token-" + uniqueToken();

        List<Long> seededIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            seededIds.add(insertChunk(kb, node, content));
        }

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(content), 20, 3));

        assertEquals(3, results.size(), "finalTopK=3 must cap the result at exactly 3 chunks");
        Set<Long> returnedIds = chunkIdsOf(results);
        Set<Long> expectedTop3 = new HashSet<>(seededIds.subList(0, 3));
        assertEquals(expectedTop3, returnedIds,
                "with identical keyword scores the lowest ids rank first");
    }

    /**
     * The keyword path must surface an exact rare phrase at rank 1 even when the vector path
     * ranks a different chunk first (that is the premise of the hybrid: each path can disagree).
     */
    @Test
    void keywordPathFindsExactPhrase() {
        long kb = createKb("keyword-phrase");
        long node = createNode(kb, null);
        String phrase = "紫微斗数飞星派秘传口诀" + uniqueToken();

        // A: the rare phrase, deliberately fed a vector opposite to the query vector (cosine
        // distance 2 = worst possible), so the vector path ranks it LAST.
        // B: unrelated content, fed the exact query vector, so the vector path ranks it FIRST.
        float[] queryVector = embeddingGateway.embed(modelIdOf(kb), List.of(phrase)).get(0);
        long phraseChunk = insertChunk(kb, node, phrase, negate(queryVector));
        long vectorTopChunk = insertChunk(kb, node, "毫不相干的说明文本unrelated-body",
                queryVector);

        // Verify the premise directly with the same pgvector operator the service uses.
        Long vectorTop1 = jdbc.queryForObject("""
                SELECT c.id FROM chunks c
                WHERE c.kb_id = ? AND c.embedding IS NOT NULL
                ORDER BY c.embedding <=> ?::vector
                LIMIT 1
                """, Long.class, kb, toVectorText(queryVector));
        assertEquals(vectorTopChunk, vectorTop1, "the vector path must rank B first (premise)");

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(phrase), 20, 3));

        assertFalse(results.isEmpty(), "the phrase query must return results");
        assertEquals(phraseChunk, results.get(0).chunkId(),
                "the exact-phrase chunk must rank first after fusion despite losing the vector path");
    }

    /** Empty kbIds is a validation failure (R7). */
    @Test
    void rejectsEmptyKbIds() {
        AppException exception = assertThrows(AppException.class, () -> retrievalService.retrieve(
                new RetrievalQuery(List.of(), Map.of(), List.of("任意查询"), 20, 8)));
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getCode());
    }

    /** Non-positive topK values fall back to the documented defaults (20 per KB / 8 final). */
    @Test
    void nonPositiveTopKFallsBackToDefaults() {
        long kb = createKb("topk-defaults");
        long node = createNode(kb, null);
        String content = "默认数量default-topk-token-" + uniqueToken();
        for (int i = 0; i < 10; i++) {
            insertChunk(kb, node, content);
        }

        List<RetrievedChunk> results = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(content), 0, 0));

        assertEquals(8, results.size(),
                "perKbTopK=0 and finalTopK=0 must fall back to the defaults 20/8, not clamp to 1");
    }

    /** Passing the same KB twice must neither duplicate results nor double the RRF score. */
    @Test
    void duplicateKbIdsAreCollapsed() {
        long kb = createKb("dup-kb");
        long node = createNode(kb, null);
        String content = "重复知识库去重dup-token-" + uniqueToken();
        insertChunk(kb, node, content);
        insertChunk(kb, node, content);

        List<RetrievedChunk> once = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb), Map.of(), List.of(content), 20, 8));
        List<RetrievedChunk> twice = retrievalService.retrieve(new RetrievalQuery(
                List.of(kb, kb), Map.of(), List.of(content), 20, 8));

        Set<Long> ids = new HashSet<>();
        for (RetrievedChunk chunk : twice) {
            assertTrue(ids.add(chunk.chunkId()), "duplicated kbIds must not duplicate results: " + chunk);
        }
        assertEquals(once, twice,
                "[A, A] must produce the same chunks with the same fused scores as [A]");
    }

    // ------------------------------------------------------------------ RrfFusion unit tests

    /** Fusion follows rank, not the carried-over raw score field. */
    @Test
    void rrfOrdersByRankNotRawScore() {
        RetrievedChunk a = chunkWithScore(1, 9999d);
        RetrievedChunk b = chunkWithScore(2, 0.001d);
        RetrievedChunk c = chunkWithScore(3, 0.0001d);

        // L1 ranks a first (raw 9999), b second; L2 ranks b first (raw 8888), c second.
        // Sorting by raw score would give [a, b, c]; rank-based fusion gives [b, a, c].
        List<List<RetrievedChunk>> lists = List.of(
                List.of(chunkWithScore(1, 9999d), chunkWithScore(2, 0.001d)),
                List.of(chunkWithScore(2, 8888d), chunkWithScore(3, 0.0001d)));

        List<RetrievedChunk> fused = new RrfFusion().fuse(lists, 10);

        assertEquals(List.of(2L, 1L, 3L), orderedIds(fused),
                "fused order must follow 1/(60+rank) sums, not raw scores");
        assertEquals(1.0 / 61 + 1.0 / 62, fused.get(0).score(), 1e-12, "b = rank1(L2) + rank2(L1)");
        assertEquals(1.0 / 61, fused.get(1).score(), 1e-12, "a = rank1(L1) only");
        assertEquals(1.0 / 62, fused.get(2).score(), 1e-12, "c = rank2(L2) only");
        assertEquals(a.score(), 9999d, "fuse must not mutate its inputs");
        assertEquals(b.score(), 0.001d, "fuse must not mutate its inputs");
        assertEquals(c.score(), 0.0001d, "fuse must not mutate its inputs");
    }

    /** A chunk present in several lists appears once with the summed contribution. */
    @Test
    void rrfSumsAcrossListsAndDeduplicates() {
        List<List<RetrievedChunk>> lists = List.of(
                List.of(chunkWithScore(1, 0.5d)),
                List.of(chunkWithScore(1, 0.9d), chunkWithScore(2, 0.1d)));

        List<RetrievedChunk> fused = new RrfFusion().fuse(lists, 10);

        assertEquals(2, fused.size(), "the chunk in both lists must appear exactly once");
        assertEquals(1L, fused.get(0).chunkId());
        assertEquals(2.0 / 61, fused.get(0).score(), 1e-12,
                "score must be the sum of 1/(60+rank) across both lists");
        assertEquals(2L, fused.get(1).chunkId(), "the singly-listed chunk ranks second");
        assertEquals(1.0 / 62, fused.get(1).score(), 1e-12, "the singly-listed chunk keeps one contribution");
    }

    /** Equal scores always yield the same stable order (lower chunkId first), call after call. */
    @Test
    void rrfTieBreaksDeterministically() {
        // Each chunk ranks first in exactly one list: identical scores 1/61.
        List<List<RetrievedChunk>> lists = List.of(
                List.of(chunkWithScore(7, 0.3d)),
                List.of(chunkWithScore(4, 0.3d)));

        List<List<RetrievedChunk>> listsSwapped = List.of(
                List.of(chunkWithScore(4, 0.3d)),
                List.of(chunkWithScore(7, 0.3d)));

        RrfFusion fusion = new RrfFusion();
        List<RetrievedChunk> first = fusion.fuse(lists, 10);
        assertEquals(1.0 / 61, first.get(0).score(), 1e-12);
        assertEquals(1.0 / 61, first.get(1).score(), 1e-12, "the tie must be exact");

        for (int i = 0; i < 5; i++) {
            assertEquals(List.of(4L, 7L), orderedIds(fusion.fuse(lists, 10)),
                    "repeated calls must return a stable order");
            assertEquals(List.of(4L, 7L), orderedIds(fusion.fuse(listsSwapped, 10)),
                    "the tie-break must not depend on input list order");
        }
    }

    /** finalTopK caps the DEDUPLICATED result: one chunk in six lists still counts once. */
    @Test
    void finalTopKCapsAfterDedup() {
        // Chunk 1 appears in six lists (rank 1 everywhere) → 6/61. Chunk 2 appears twice at
        // rank 2 → 2/62; chunk 3 once at rank 2 → 1/62. Capping at 2 must yield exactly two
        // distinct chunks (the heavily duplicated one first), not three rows or one per list.
        List<List<RetrievedChunk>> lists = List.of(
                List.of(chunkWithScore(1, 9), chunkWithScore(2, 8)),
                List.of(chunkWithScore(1, 7)),
                List.of(chunkWithScore(1, 6), chunkWithScore(3, 5)),
                List.of(chunkWithScore(1, 4)),
                List.of(chunkWithScore(1, 3)),
                List.of(chunkWithScore(1, 2), chunkWithScore(2, 1)));

        List<RetrievedChunk> fused = new RrfFusion().fuse(lists, 2);

        assertEquals(2, fused.size(), "finalTopK=2 must cap after dedup");
        assertEquals(2, new HashSet<>(orderedIds(fused)).size(),
                "the two returned rows must be distinct chunks");
        assertEquals(1L, fused.get(0).chunkId(), "the chunk in six lists ranks first");
        assertEquals(6.0 / 61, fused.get(0).score(), 1e-12,
                "one contribution per list: six lists at rank 1");
        assertEquals(2L, fused.get(1).chunkId(),
                "second place goes to two rank-2 contributions, not one");
        assertEquals(2.0 / 62, fused.get(1).score(), 1e-12);
    }

    // ------------------------------------------------------------------ helpers

    private static Set<Long> chunkIdsOf(List<RetrievedChunk> chunks) {
        Set<Long> ids = new HashSet<>();
        for (RetrievedChunk chunk : chunks) {
            ids.add(chunk.chunkId());
        }
        return ids;
    }

    /** Ordered ids — for assertions on ranking, where a Set comparison would lose the order. */
    private static List<Long> orderedIds(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::chunkId).toList();
    }

    private static RetrievedChunk chunkWithScore(long chunkId, double score) {
        return new RetrievedChunk(chunkId, 1000L + chunkId, 7L, null, "doc.md", null, "content", score);
    }

    private static String uniqueToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private long createKb(String tag) {
        Long id = jdbc.queryForObject("""
                INSERT INTO knowledge_bases (name, embedding_model_id)
                VALUES (?, ?)
                RETURNING id
                """, Long.class, "retrieval-" + tag + "-" + UUID.randomUUID(), embeddingFixture.modelId());
        if (id == null) {
            throw new IllegalStateException("kb insert did not return an id");
        }
        createdKbIds.add(id);
        return id;
    }

    private long modelIdOf(long kbId) {
        return jdbc.queryForObject(
                "SELECT embedding_model_id FROM knowledge_bases WHERE id = ?", Long.class, kbId);
    }

    /** Inserts a root node and returns its id; path follows the {@code n{id}} convention. */
    private long createNode(long kbId, Long parentId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_nodes (kb_id, parent_id, name, path, sort_order)
                VALUES (?, ?, ?, 'placeholder', 0)
                RETURNING id
                """, Long.class, kbId, parentId, "node-" + UUID.randomUUID());
        if (id == null) {
            throw new IllegalStateException("node insert did not return an id");
        }
        if (parentId == null) {
            jdbc.update("UPDATE kb_nodes SET path = CAST('n' || ? AS ltree) WHERE id = ?", id, id);
        } else {
            String parentPath = jdbc.queryForObject(
                    "SELECT path FROM kb_nodes WHERE id = ?", String.class, parentId);
            jdbc.update("UPDATE kb_nodes SET path = CAST(? || '.n' || ? AS ltree) WHERE id = ?",
                    parentPath, id, id);
        }
        return id;
    }

    private long insertDocument(long kbId, Long nodeId, String status) {
        Long id = jdbc.queryForObject("""
                INSERT INTO documents (kb_id, node_id, filename, object_key, size, status)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, kbId, nodeId, "retrieval-doc-" + UUID.randomUUID() + ".md",
                "kb/" + kbId + "/" + UUID.randomUUID() + "/doc.md", 512L, status);
        if (id == null) {
            throw new IllegalStateException("document insert did not return an id");
        }
        return id;
    }

    private long insertChunk(long kbId, Long nodeId, String content) {
        long docId = insertDocument(kbId, nodeId, "READY");
        return insertChunk(kbId, nodeId, content, docId);
    }

    /** Seeds a chunk under an explicit document with no embedding (keyword path only). */
    private long insertChunk(long kbId, Long nodeId, String content, long documentId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chunks (document_id, kb_id, node_id, seq, content)
                VALUES (?, ?, ?, 0, ?)
                RETURNING id
                """, Long.class, documentId, kbId, nodeId, content);
        if (id == null) {
            throw new IllegalStateException("chunk insert did not return an id");
        }
        return id;
    }

    /** Seeds a chunk with an explicit embedding written as pgvector text. */
    private long insertChunk(long kbId, Long nodeId, String content, float[] embedding) {
        long docId = insertDocument(kbId, nodeId, "READY");
        Long id = jdbc.queryForObject("""
                INSERT INTO chunks (document_id, kb_id, node_id, seq, content, embedding)
                VALUES (?, ?, ?, 0, ?, ?::vector)
                RETURNING id
                """, Long.class, docId, kbId, nodeId, content, toVectorText(embedding));
        if (id == null) {
            throw new IllegalStateException("chunk insert did not return an id");
        }
        return id;
    }

    private float[] negate(float[] vector) {
        float[] copy = vector.clone();
        for (int i = 0; i < copy.length; i++) {
            copy[i] = -copy[i];
        }
        return copy;
    }

    /** Mirrors the service's pgvector text formatting: {@code [0.1,0.2,...]}. */
    private static String toVectorText(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        return sb.append(']').toString();
    }
}
