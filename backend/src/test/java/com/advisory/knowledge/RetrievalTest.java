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
 * 混合检索的集成测试：按知识库分别跑向量与 pg_trgm 关键词路径，再用 RRF（k=60）融合；
 * 同时覆盖跨知识库的 {@code nodeScope}（ltree 子树）过滤、借助 {@code documents} 关联的文档状态过滤、
 * REINDEXING 拒绝，以及融合算法本身的单元契约。
 *
 * <p>凡是排序必须可控的用例（范围过滤、finalTopK、关键词与向量的分歧），都用 {@link JdbcTemplate} 直接写入片段，
 * 这样断言不依赖向量化的具体行为；只有 {@link #keywordPathFindsExactPhrase()} 需要真实走向量路径，
 * 用的是 {@code TestEmbeddingGatewayConfig} 提供的确定性桩。</p>
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
        // 文档与 kb_nodes 都引用该知识库；片段随文档级联删除。
        for (Long kbId : createdKbIds) {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        }
        createdKbIds.clear();
        if (embeddingFixture != null) {
            embeddingFixture.close();
            embeddingFixture = null;
        }
    }

    // --------------------------------------------------------- 范围与状态过滤

    /** 范围按知识库各自生效：A 限定到节点 X 后，既不能返回 A/Y 的片段，也不能返回 B 的任何片段。 */
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

        // 两个知识库都设了范围：A 限到节点 X（共享内容就在这里），B 限到另一个不含该内容的节点——
        // 因此 A/Y、A 的未挂节点片段以及 B 的那份拷贝都不该出现。
        // 另外多传一个不属于 kbIds 的知识库条目，必须被忽略（R4）且不影响结果。
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

    /** 范围内给父节点 id 即选中整棵子树：子节点与孙节点都包含在内。 */
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

    /** 已 DELETED 的文档，其片段即使关键词完全命中也不会出现。 */
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

    /** 非终态文档（PENDING/FAILED）由同一个关联条件过滤掉。 */
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

    /** 悬空的范围 id 直接被忽略：不抛异常，也不会匹配到任何内容。 */
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

    /** 重建中会以 409 KB_REINDEXING 阻断检索（Task 7 交接时约定的义务）。 */
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
     * 每个 (kbId, path, query) 三元组都必须作为独立的有序列表进入 {@link RrfFusion#fuse}：
     * 下面的片段在两条查询的关键词列表里都排第 2，因此累计 {@code 2 × 1/62}，反超只排过一次第 1 的
     * {@code 1/61}。评审前的实现会按片段只保留最好的一次贡献（{@code 1/62}），结果反而把它排到最后。
     */
    @Test
    void multiQueryContributionsAccumulate() {
        long kb = createKb("multi-query");
        long node = createNode(kb, null);
        // 两段文本刻意不共享任何三元组：q1 与 q2 的关键词列表各自只包含自己的精确命中，再加上那个共同片段。
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

    /** 只有 REINDEXING 才拦：FAILED 状态的知识库必须仍可检索（设计 §10）。 */
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

    /** 缺少范围键等于整库检索：所有节点、乃至未挂节点的片段都可入选。 */
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

    /** finalTopK 精确截断融合后的列表。 */
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
     * 即便向量路径把另一个片段排在首位，关键词路径也必须把某个罕见短语的精确命中顶到第 1 名
     * （这正是混合检索的前提：两条路径本来就允许各执一词）。
     */
    @Test
    void keywordPathFindsExactPhrase() {
        long kb = createKb("keyword-phrase");
        long node = createNode(kb, null);
        String phrase = "紫微斗数飞星派秘传口诀" + uniqueToken();

        // A：含罕见短语，刻意喂一个与查询向量相反的向量（余弦距离 2，最差情况），让向量路径把它排到最后。
        // B：无关内容，喂的正是查询向量本身，于是向量路径把它排到第一。
        float[] queryVector = embeddingGateway.embed(modelIdOf(kb), List.of(phrase)).get(0);
        long phraseChunk = insertChunk(kb, node, phrase, negate(queryVector));
        long vectorTopChunk = insertChunk(kb, node, "毫不相干的说明文本unrelated-body",
                queryVector);

        // 用与 Service 完全相同的 pgvector 运算符直接验证这个前提，避免断言建立在假设之上。
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

    /** kbIds 为空属于校验失败（R7）。 */
    @Test
    void rejectsEmptyKbIds() {
        AppException exception = assertThrows(AppException.class, () -> retrievalService.retrieve(
                new RetrievalQuery(List.of(), Map.of(), List.of("任意查询"), 20, 8)));
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getCode());
    }

    /** topK 传非正数时回落到文档约定的默认值（每库 20、最终 8）。 */
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

    /** 同一个知识库传两次：结果不重复，RRF 分数也不翻倍。 */
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

    // ------------------------------------------------------------------ RrfFusion 单元测试

    /** 融合只认排名，不认同路结果里携带的原始分数字段。 */
    @Test
    void rrfOrdersByRankNotRawScore() {
        RetrievedChunk a = chunkWithScore(1, 9999d);
        RetrievedChunk b = chunkWithScore(2, 0.001d);
        RetrievedChunk c = chunkWithScore(3, 0.0001d);

        // L1 把 a 排第一（原始分 9999）、b 第二；L2 把 b 排第一（原始分 8888）、c 第二。
        // 按原始分排序会得到 [a, b, c]；按排名融合则应得到 [b, a, c]。
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

    /** 出现在多个列表中的片段只保留一条，分数为各次贡献之和。 */
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

    /** 分数相同时顺序必须稳定：每次调用都是 chunkId 较小的在前。 */
    @Test
    void rrfTieBreaksDeterministically() {
        // 每个片段各在一个列表中排第一：三者分数都是 1/61。
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

    /** finalTopK 作用于去重之后：一个片段即使出现在六个列表里也只算一条。 */
    @Test
    void finalTopKCapsAfterDedup() {
        // 片段 1 出现在六个列表里且处处第一 → 6/61；片段 2 两次排第 2 → 2/62；片段 3 一次排第 2 → 1/62。
        // 截断到 2 条时必须恰好给出两个不同片段（重复最多的在前），而不是三行，也不是每个列表一条。
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

    // ------------------------------------------------------------------ 辅助方法

    private static Set<Long> chunkIdsOf(List<RetrievedChunk> chunks) {
        Set<Long> ids = new HashSet<>();
        for (RetrievedChunk chunk : chunks) {
            ids.add(chunk.chunkId());
        }
        return ids;
    }

    /** 按顺序返回 id；排序断言不能用 Set 比较，否则顺序信息就丢了。 */
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

    /** 插入一个根节点并返回其 id；路径遵循 {@code n{id}} 约定。 */
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

    /** 在指定文档下写入一个不带向量的片段，只用于关键词路径。 */
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

    /** 写入一个带显式向量的片段，向量以 pgvector 文本形式给出。 */
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

    /** 复刻 Service 的 pgvector 文本格式：{@code [0.1,0.2,...]}。 */
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
