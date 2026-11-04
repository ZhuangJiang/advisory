package com.advisory.knowledge;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import com.advisory.knowledge.service.DocumentIngestWorker;
import com.advisory.knowledge.service.StorageService;
import com.advisory.provider.service.CryptoService;
import com.advisory.support.EmbeddingModelFixture;
import com.advisory.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重建编排的集成测试：接口仅限管理员并返回 202；{@code reindex_status = 'REINDEXING'} 期间
 * 上传（presign 与 create）被 409 {@code KB_REINDEXING} 挡住；正在重建时不能重复触发；
 * 重建失败会体现为 {@code FAILED}（文档同样如此），之后再触发可以恢复；重建使用当前的
 * {@code chunk_config} 重新切片；没有向量模型的知识库会被拒绝且不会卡在中间状态。
 *
 * <p>网关的 mock 是确定性的：用 {@link CountDownLatch} 把重建冻结在中途，这样「状态是 REINDEXING」
 * 与「第二次调用返回 409」这两处断言就不会变成和完成速度赛跑的测试。</p>
 */
class ReindexTest extends IntegrationTestBase {

    private static final String PASSWORD = "reindex-pass-1";
    private static final Duration WORKER_IDLE_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DocumentIngestWorker worker;

    @Autowired
    private StorageService storage;

    @Autowired
    private CryptoService cryptoService;

    @Value("${app.embedding.dimension}")
    private int dimension;

    @MockitoBean
    private EmbeddingGateway embeddingGateway;

    private String adminToken;
    private String userToken;
    private final List<Long> createdKbIds = new ArrayList<>();
    private EmbeddingModelFixture embeddingFixture;

    @BeforeEach
    void createFixtures() throws Exception {
        User admin = createUser(UserRole.ADMIN, "admin");
        User user = createUser(UserRole.USER, "user");
        adminToken = login(admin.getUsername());
        userToken = login(user.getUsername());
        embeddingFixture = EmbeddingModelFixture.create(jdbcTemplate, cryptoService);
    }

    @AfterEach
    void cleanup() throws Exception {
        worker.awaitIdle(WORKER_IDLE_TIMEOUT);
        for (Long kbId : createdKbIds) {
            jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        }
        createdKbIds.clear();
        if (embeddingFixture != null) {
            embeddingFixture.close();
            embeddingFixture = null;
        }
    }

    @Test
    void reindexingBlocksUpload() throws Exception {
        long kbId = createKb(true);
        jdbcTemplate.update(
                "UPDATE knowledge_bases SET reindex_status = 'REINDEXING' WHERE id = ?", kbId);

        mockMvc.perform(post("/api/documents/presign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kbId\":" + kbId + ",\"nodeId\":null,\"filename\":\"blocked.txt\",\"size\":10}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40905));

        mockMvc.perform(post("/api/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kbId\":" + kbId + ",\"nodeId\":null,\"filename\":\"blocked.txt\","
                                + "\"objectKey\":\"kb/" + kbId + "/" + UUID.randomUUID() + "/blocked.txt\","
                                + "\"size\":10}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40905));
    }

    @Test
    void reindexEndpointReturns202AndCompletes() throws Exception {
        long kbId = createKb(true);
        long documentId = makeDocument(kbId, "# reindex\ndoc body awaiting rebuild");

        CountDownLatch blocker = new CountDownLatch(1);
        given(embeddingGateway.embed(anyLong(), anyList()))
                .willAnswer(invocation -> {
                    assertTrue(blocker.await(30, TimeUnit.SECONDS), "release latch was not triggered");
                    return zeroVectors(invocation.getArgument(1));
                });

        mockMvc.perform(post("/api/knowledge-bases/{id}/reindex", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0));

        // 重建线程被确定性地卡在第一次向量化里，因此这里不存在「和完成赛跑」的问题。
        // 此时文档已被重置，处在流水线的某个位置（刚重置时是 PENDING，现在可能是 PARSING/CHUNKING/EMBEDDING）——
        // 要断言的是它正在被重建，而不是它恰好停在某个特定中间态。
        assertEquals("REINDEXING", reindexStatusOf(kbId));
        assertTrue(List.of("PENDING", "PARSING", "CHUNKING", "EMBEDDING").contains(statusOf(documentId)),
                "reindex must reset and re-ingest the document");

        blocker.countDown();
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "reindex task must finish");
        assertEquals("READY", reindexStatusOf(kbId));
        assertEquals("READY", statusOf(documentId));
    }

    @Test
    void reindexTwiceConflicts() throws Exception {
        long kbId = createKb(true);
        makeDocument(kbId, "reindex twice body");

        CountDownLatch blocker = new CountDownLatch(1);
        given(embeddingGateway.embed(anyLong(), anyList()))
                .willAnswer(invocation -> {
                    assertTrue(blocker.await(30, TimeUnit.SECONDS), "release latch was not triggered");
                    return zeroVectors(invocation.getArgument(1));
                });

        mockMvc.perform(post("/api/knowledge-bases/{id}/reindex", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/knowledge-bases/{id}/reindex", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40905));

        blocker.countDown();
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT));
    }

    @Test
    void reindexFailureSetsFailed() throws Exception {
        long kbId = createKb(true);
        long documentId = makeDocument(kbId, "a doomed reindex run");

        when(embeddingGateway.embed(anyLong(), anyList()))
                .thenThrow(new RuntimeException("embedding provider down"));

        mockMvc.perform(post("/api/knowledge-bases/{id}/reindex", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isAccepted());
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "failed reindex must finish");

        assertEquals("FAILED", statusOf(documentId), "the failing document must be FAILED");
        assertEquals("FAILED", reindexStatusOf(kbId), "the knowledge base must be FAILED");

        // 提供商恢复之后，再触发一次重建必须把文档与知识库都修好。
        // 用 doAnswer 而不是 when()：通过 when() 重新打桩会先调用一次 mock，
        // 从而在装上新答案之前就把上一版「提供商挂了」的桩触发一遍。
        doAnswer(invocation -> zeroVectors(invocation.getArgument(1)))
                .when(embeddingGateway).embed(anyLong(), anyList());
        mockMvc.perform(post("/api/knowledge-bases/{id}/reindex", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isAccepted());
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "recovered reindex must finish");

        assertEquals("READY", statusOf(documentId));
        assertEquals("READY", reindexStatusOf(kbId));
    }

    @Test
    void reindexRebuildsChunks() throws Exception {
        long kbId = createKb(true);
        long documentId = makeDocument(kbId, "x".repeat(1200));
        worker.enqueue(documentId);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "initial ingest must finish");
        Integer countBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertNotNull(countBefore);
        assertTrue(countBefore <= 2, "default config must keep " + countBefore + " chunks at most");

        // 强制让重建产出完全不同的片段数量，才能证明切片配置真的被重新应用。
        jdbcTemplate.update("""
                UPDATE knowledge_bases SET chunk_config = '{"minChars": 50, "maxChars": 120, "overlapRatio": 0.1}'::jsonb
                WHERE id = ?
                """, kbId);
        when(embeddingGateway.embed(anyLong(), anyList())).thenAnswer(invocation ->
                zeroVectors(invocation.getArgument(1)));

        mockMvc.perform(post("/api/knowledge-bases/{id}/reindex", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isAccepted());
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "rebuilding reindex must finish");

        Integer countAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertNotNull(countAfter);
        assertTrue(countAfter > countBefore, "smaller maxChars must rebuild into more chunks, was "
                + countBefore + ", now " + countAfter);
        Integer distinctSeqs = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT seq) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertEquals(countAfter, distinctSeqs, "the rebuild must not leave duplicate seq values");
    }

    @Test
    void userCannotReindex() throws Exception {
        long kbId = createKb(true);
        mockMvc.perform(post("/api/knowledge-bases/{id}/reindex", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        assertEquals("READY", reindexStatusOf(kbId), "a rejected reindex must not mutate the status");
    }

    @Test
    void reindexWithoutEmbeddingModelRejected() throws Exception {
        long kbId = createKb(false);

        mockMvc.perform(post("/api/knowledge-bases/{id}/reindex", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        assertEquals("READY", reindexStatusOf(kbId), "the kb must not stay stuck at REINDEXING");
    }

    /**
     * 如果崩溃发生在 REINDEXING 的 CAS 与后台收尾之间，知识库不能就此永久卡死：启动恢复会把这个残留状态
     * 收敛为 FAILED（不自动重启——崩溃原因未知），管理员因此可以重新触发重建。
     */
    @Test
    void reindexStatusIsSettledAfterRestart() {
        long stuckKbId = createKb(true);
        long readyKbId = createKb(true);
        jdbcTemplate.update(
                "UPDATE knowledge_bases SET reindex_status = 'REINDEXING' WHERE id = ?", stuckKbId);

        List<Long> settled = worker.recoverInterruptedReindexes();

        assertTrue(settled.contains(stuckKbId),
                "the stale REINDEXING knowledge base must be settled, got " + settled);
        assertEquals("FAILED", reindexStatusOf(stuckKbId),
                "a crashed reindex must settle to FAILED so it can be re-triggered");
        assertEquals("READY", reindexStatusOf(readyKbId), "a READY knowledge base must be untouched");
        assertEquals(List.of(), worker.recoverInterruptedReindexes(),
                "recovery must be idempotent: nothing left to settle on the second call");
    }

    /** 重建过程中 PATCH 会改动正在被读取的配置，必须拒绝（裁决 R6）。 */
    @Test
    void patchDuringReindexRejected() throws Exception {
        long kbId = createKb(true);
        String originalName = nameOf(kbId);
        jdbcTemplate.update(
                "UPDATE knowledge_bases SET reindex_status = 'REINDEXING' WHERE id = ?", kbId);

        mockMvc.perform(patch("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed-during-reindex\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40905));

        assertEquals(originalName, nameOf(kbId), "a rejected PATCH must not change the name");
    }

    // ------------------------------------------------------------------ 辅助方法

    private String nameOf(long kbId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM knowledge_bases WHERE id = ?", String.class, kbId);
    }

    private List<float[]> zeroVectors(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String ignored : texts) {
            vectors.add(new float[dimension]);
        }
        return vectors;
    }

    private long makeDocument(long kbId, String content) {
        String objectKey = "kb/" + kbId + "/" + UUID.randomUUID() + "/reindex-doc.md";
        storage.putText(objectKey, content);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO documents (kb_id, filename, object_key, size, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                RETURNING id
                """, Long.class, kbId, "reindex-doc.md", objectKey, 512L);
        if (id == null) {
            throw new IllegalStateException("insert did not return an id");
        }
        return id;
    }

    private String reindexStatusOf(long kbId) {
        return jdbcTemplate.queryForObject(
                "SELECT reindex_status FROM knowledge_bases WHERE id = ?", String.class, kbId);
    }

    private String statusOf(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId);
    }

    private User createUser(UserRole role, String prefix) {
        User user = new User("reindex-" + prefix + "-" + UUID.randomUUID(),
                passwordEncoder.encode(PASSWORD), role);
        user.setEnabled(true);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }

    private long createKb(boolean withEmbeddingModel) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_bases (name, embedding_model_id)
                VALUES (?, ?)
                RETURNING id
                """, Long.class, "reindex-kb-" + UUID.randomUUID(),
                withEmbeddingModel ? embeddingFixture.modelId() : null);
        if (id == null) {
            throw new IllegalStateException("insert did not return an id");
        }
        createdKbIds.add(id);
        return id;
    }
}
