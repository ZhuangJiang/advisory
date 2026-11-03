package com.advisory.knowledge;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import com.advisory.knowledge.service.DocumentIngestWorker;
import com.advisory.knowledge.service.StorageService;
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
 * Reindex orchestration: the endpoint is ADMIN-only and returns 202, {@code reindex_status =
 * 'REINDEXING'} blocks uploads (presign and create) with 409 {@code KB_REINDEXING}, a running
 * reindex cannot be re-triggered, a failed reindex shows up as {@code FAILED} (documents included)
 * and a later reindex recovers, rebuilding chunks with the current {@code chunk_config}, and a
 * knowledge base without an embedding model is rejected without getting stuck.
 *
 * <p>The gateway mock is deterministic: a {@link CountDownLatch} lets the test freeze the running
 * reindex mid-flight so {@code REINDEXING} and the 409-on-second-call asserts are not races.</p>
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
        embeddingFixture = EmbeddingModelFixture.create(jdbcTemplate);
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

        // The reindex thread is deterministically blocked inside the first embed, so this is not a
        // race against completion. The document has already been reset and is somewhere on the
        // pipeline (PENDING right after the reset, PARSING/CHUNKING/EMBEDDING by now) — the point
        // is that it is being rebuilt, not that it sits at any single intermediate step.
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

        // The provider recovers; a later reindex must repair documents and the knowledge base.
        // doAnswer, not when(): re-stubbing through when() would re-invoke the mock and re-throw
        // the previous "provider down" stub before the new answer is installed.
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

        // Force a completely different chunk count for the rebuild.
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
     * A crash between the REINDEXING CAS and the background settle must not brick the knowledge
     * base forever: startup recovery settles the stale status to FAILED (no auto-restart — the
     * crash cause is unknown), so the admin can re-trigger the reindex.
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

    /** PATCH mid-reindex would mutate the config the running rebuild is reading (ruling R6). */
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

    // ------------------------------------------------------------------ helpers

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
