package com.advisory.knowledge;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.domain.DocumentStatus;
import com.advisory.knowledge.ingest.ChunkWriter;
import com.advisory.knowledge.ingest.EmbeddingClientFactory;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import com.advisory.knowledge.ingest.TextChunk;
import com.advisory.knowledge.service.DocumentIngestWorker;
import com.advisory.knowledge.service.StorageService;
import com.advisory.provider.service.CryptoService;
import com.advisory.support.EmbeddingModelFixture;
import com.advisory.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real ingest pipeline against the real database: the dimension guard aborts <em>before any
 * chunk delete</em>, chunk rewrite on re-runs is idempotent, chunking is structure-aware, and the
 * parsed Markdown lands in MinIO with {@code documents.parsed_key} set. The {@link EmbeddingGateway}
 * is mocked so no test calls out to a provider; a {@link ChunkWriter} spy verifies the chunk list
 * the worker handed over.
 *
 * <p>Knowledge bases get a wired {@code embedding_model_id} because the worker must fail documents
 * whose knowledge base has none (ruling R3); the no-model failure path is covered by
 * {@code ReindexTest#reindexWithoutEmbeddingModelRejected}.</p>
 */
class EmbeddingWriteTest extends IntegrationTestBase {

    private static final String PASSWORD = "embedding-write-pass-1";
    private static final Duration WORKER_IDLE_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DocumentIngestWorker worker;

    @Autowired
    private StorageService storage;

    @Autowired
    private EmbeddingClientFactory embeddingClientFactory;

    @Value("${app.embedding.dimension}")
    private int dimension;

    @MockitoBean
    private EmbeddingGateway embeddingGateway;

    @MockitoSpyBean
    private ChunkWriter chunkWriter;

    private String adminToken;
    private final List<Long> createdKbIds = new ArrayList<>();
    private EmbeddingModelFixture embeddingFixture;

    @BeforeEach
    void createFixtures() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        adminToken = login(admin.getUsername());
        embeddingFixture = EmbeddingModelFixture.create(jdbcTemplate, cryptoService);
    }

    @AfterEach
    void cleanup() {
        worker.awaitIdle(WORKER_IDLE_TIMEOUT);
        for (Long kbId : createdKbIds) {
            // documents and kb_nodes reference the kb; chunks cascade off documents.
            jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        }
        createdKbIds.clear();
        if (embeddingFixture != null) {
            embeddingFixture.close();
            embeddingFixture = null;
        }
    }

    @Test
    void dimensionMismatchAbortsWithoutDeletingExistingChunks() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        String objectKey = uploadObject(kbId, "some parsed body");
        long documentId = insertDocumentRow(kbId, nodeId, "dim-guard.txt", objectKey);
        insertChunk(documentId, kbId, nodeId, 0, "old-broken-a");
        insertChunk(documentId, kbId, nodeId, 1, "old-broken-b");

        // Buggy provider output: one 3-dimensional vector per chunk instead of 1024.
        when(embeddingGateway.embed(anyLong(), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String ignored : texts) {
                vectors.add(new float[] {1f, 2f, 3f});
            }
            return vectors;
        });

        worker.enqueue(documentId);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "failed ingest must finish");

        assertEquals("FAILED", statusOf(documentId));
        String errorMsg = jdbcTemplate.queryForObject(
                "SELECT error_msg FROM documents WHERE id = ?", String.class, documentId);
        assertNotNull(errorMsg, "failure must carry an error message");
        assertTrue(errorMsg.contains("dimension"), "error must mention the dimension, got: " + errorMsg);

        // The DELETE lives in the same transaction as the batch INSERT; the pre-validation abort
        // means the seeded chunks must still be there, unchanged.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertEquals(2, count, "old chunks must survive the aborted ingest");
        assertEquals("old-broken-a", jdbcTemplate.queryForObject(
                "SELECT content FROM chunks WHERE document_id = ? AND seq = 0", String.class, documentId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT embedding FROM chunks WHERE document_id = ? AND seq = 0", Object.class, documentId),
                "old chunks must not have been re-embedded by the aborted run");
    }

    @Test
    void rerunDoesNotDuplicateChunks() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        stubSuccessfulEmbedding();
        String objectKey = uploadObject(kbId, "first and second run share the object");
        long documentId = insertDocumentRow(kbId, nodeId, "rerun.txt", objectKey);

        worker.enqueue(documentId);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "first ingest must finish");
        assertEquals("READY", statusOf(documentId));
        Integer countAfterFirst = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertNotNull(countAfterFirst);
        assertTrue(countAfterFirst >= 1, "a non-empty document must produce at least one chunk");

        // Force a second full run of the same document through the pipeline.
        assertTrue(worker.transition(documentId, DocumentStatus.READY, DocumentStatus.PENDING, null));
        worker.enqueue(documentId);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "second ingest must finish");

        Integer countAfterRerun = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertEquals(countAfterFirst, countAfterRerun, "re-running the same document must not duplicate chunks");
        Integer distinctSeqs = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT seq) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertEquals(countAfterRerun, distinctSeqs, "seq must stay unique after the re-run");
        Integer minSeq = jdbcTemplate.queryForObject(
                "SELECT min(seq) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertEquals(0, minSeq, "seq must start at 0 after the re-run");
    }

    @Test
    void ingestWritesChunksWithStructure() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "heading-node");
        stubSuccessfulEmbedding();
        String markdown = "# 概览\n" + "alpha".repeat(150) + "\n\n" + "# 细节\n" + "beta".repeat(150);
        String objectKey = uploadObject(kbId, markdown);
        long documentId = insertDocumentRow(kbId, nodeId, "structure.md", objectKey);

        worker.enqueue(documentId);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "ingest must finish");

        assertEquals("READY", statusOf(documentId));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertNotNull(count);
        assertTrue(count >= 2, "two heading sections must produce at least two chunks, got " + count);

        Integer withoutEmbedding = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ? AND embedding IS NULL",
                Integer.class, documentId);
        assertEquals(0, withoutEmbedding, "every chunk must carry an embedding");

        Integer wrongNode = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ? AND node_id <> ?",
                Integer.class, documentId, nodeId);
        assertEquals(0, wrongNode, "every chunk must point at the document's node");

        // headingPath is not persisted; capture the list the worker handed to the writer.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextChunk>> chunkCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(chunkWriter).replaceAll(
                eq(documentId), eq(kbId), eq(nodeId), chunkCaptor.capture(), anyList());
        List<TextChunk> handedOver = chunkCaptor.getValue();
        assertEquals(count, handedOver.size(), "the DB must hold exactly the chunks that were embedded");
        assertTrue(handedOver.stream().anyMatch(chunk -> !chunk.headingPath().isEmpty()),
                "at least one chunk must carry a non-empty heading path");
    }

    /**
     * The global chunking contract caps overlap at 15%; a config beyond that range is a document
     * failure ({@code VALIDATION_FAILED} carried in {@code error_msg}) — not an exception escaping
     * the worker thread and not silently accepted.
     */
    @Test
    void overlapRatioOutOfRangeFailsDocument() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        stubSuccessfulEmbedding();
        String objectKey = uploadObject(kbId, "body that must not be chunked with a bad overlap");
        long documentId = insertDocumentRow(kbId, nodeId, "bad-overlap.md", objectKey);
        jdbcTemplate.update("""
                UPDATE knowledge_bases
                SET chunk_config = '{"minChars": 50, "maxChars": 120, "overlapRatio": 0.4}'::jsonb
                WHERE id = ?
                """, kbId);

        worker.enqueue(documentId);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "failed ingest must finish");

        assertEquals("FAILED", statusOf(documentId),
                "an out-of-range overlapRatio must fail the document, not escape the thread");
        String errorMsg = jdbcTemplate.queryForObject(
                "SELECT error_msg FROM documents WHERE id = ?", String.class, documentId);
        assertNotNull(errorMsg, "the failure must carry an error message");
        assertTrue(errorMsg.contains("0.10") && errorMsg.contains("0.15"),
                "error must name the allowed range [0.10, 0.15], got: " + errorMsg);
        verify(embeddingGateway, never()).embed(anyLong(), anyList());
    }

    /**
     * A {@code models} row whose declared dimension disagrees with {@code app.embedding.dimension}
     * must be rejected before any provider network call is made.
     */
    @Test
    void modelDimensionCrossCheckRejectsBeforeProviderCall() {
        long modelId = embeddingFixture.modelId();
        jdbcTemplate.update("UPDATE models SET dimension = 512 WHERE id = ?", modelId);

        AppException exception = assertThrows(AppException.class,
                () -> embeddingClientFactory.forEmbedding(modelId));

        assertEquals(ErrorCode.EMBEDDING_DIMENSION_MISMATCH, exception.getCode());
        assertTrue(exception.getMessage().contains("dimension"),
                "message must mention the dimension, got: " + exception.getMessage());
    }

    @Test
    void parsedMarkdownIsStored() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        stubSuccessfulEmbedding();
        String markdown = "# 标题\n" + "hello parsed artifact body ".repeat(20);
        String objectKey = uploadObject(kbId, markdown);
        long documentId = insertDocumentRow(kbId, nodeId, "parsed.md", objectKey);

        worker.enqueue(documentId);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "ingest must finish");
        assertEquals("READY", statusOf(documentId));

        String parsedKey = jdbcTemplate.queryForObject(
                "SELECT parsed_key FROM documents WHERE id = ?", String.class, documentId);
        assertNotNull(parsedKey, "ingest must persist the parsed_key column");
        assertTrue(parsedKey.startsWith("kb/" + kbId + "/"), "parsed_key must sit next to the raw object");
        assertTrue(parsedKey.endsWith("/parsed.md"));

        byte[] stored = storage.getObject(parsedKey).readAllBytes();
        assertEquals(markdown, new String(stored, StandardCharsets.UTF_8),
                "the stored parsed Markdown must be the parser output");
    }

    // ------------------------------------------------------------------ helpers

    private void stubSuccessfulEmbedding() {
        when(embeddingGateway.embed(anyLong(), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String ignored : texts) {
                vectors.add(new float[dimension]);
            }
            return vectors;
        });
    }

    private String statusOf(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId);
    }

    private String uploadObject(long kbId, String content) {
        String key = "kb/" + kbId + "/" + UUID.randomUUID() + "/doc.md";
        storage.putText(key, content);
        return key;
    }

    private long insertDocumentRow(long kbId, Long nodeId, String filename, String objectKey) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO documents (kb_id, node_id, filename, object_key, size, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                RETURNING id
                """, Long.class, kbId, nodeId, filename, objectKey, 512L);
        if (id == null) {
            throw new IllegalStateException("insert did not return an id");
        }
        return id;
    }

    private void insertChunk(long documentId, long kbId, Long nodeId, int seq, String content) {
        jdbcTemplate.update("""
                INSERT INTO chunks (document_id, kb_id, node_id, seq, content)
                VALUES (?, ?, ?, ?, ?)
                """, documentId, kbId, nodeId, seq, content);
    }

    private User createUser(UserRole role) {
        User user = new User("embed-" + UUID.randomUUID(), passwordEncoder.encode(PASSWORD), role);
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

    private long createKb() throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"embedding-kb-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        embeddingFixture.wire(id);
        createdKbIds.add(id);
        return id;
    }

    private long createNode(long kbId, String name) throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases/" + kbId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }
}
