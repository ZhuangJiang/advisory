package com.advisory.knowledge;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Restart recovery: documents left in an intermediate status by a previous process are reset to
 * {@code PENDING} and re-queued, terminal rows are untouched, and a recovered document reaches
 * {@code READY} again.
 *
 * <p>{@code restartRequeuesInterruptedDocuments} calls {@code recoverInterrupted()} inside a
 * transaction that keeps the updated row lock: the worker tasks it enqueues block on their
 * compare-and-set until the test commits, so the {@code PENDING} assertion is deterministic instead
 * of racing the pipeline.</p>
 */
class IngestRecoveryTest extends IntegrationTestBase {

    private static final String PASSWORD = "ingest-recovery-pass-1";
    private static final Duration WORKER_IDLE_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private DocumentIngestWorker worker;

    @Autowired
    private StorageService storageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String adminToken;
    private final List<Long> createdKbIds = new ArrayList<>();
    private EmbeddingModelFixture embeddingFixture;

    @BeforeEach
    void createFixtures() throws Exception {
        User admin = createUser();
        adminToken = login(admin.getUsername());
        embeddingFixture = EmbeddingModelFixture.create(jdbcTemplate, cryptoService);
    }

    @AfterEach
    void cleanup() {
        worker.awaitIdle(WORKER_IDLE_TIMEOUT);
        for (Long kbId : createdKbIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE kb_id = ?", kbId);
            jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        }
        createdKbIds.clear();
        if (embeddingFixture != null) {
            // Knowledge bases referencing the model are deleted above (ON DELETE RESTRICT).
            embeddingFixture.close();
            embeddingFixture = null;
        }
    }

    @Test
    void restartRequeuesInterruptedDocuments() throws Exception {
        long kbId = createKb();
        long documentId = insertDocument(kbId, "PARSING");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<Long> requeued = tx.execute(status -> {
            List<Long> ids = worker.recoverInterrupted();
            // The bulk UPDATE ... RETURNING still holds the row lock here, so the task it enqueued
            // cannot move the row any further until this transaction commits.
            assertEquals("PENDING", statusOf(documentId), "interrupted document must be reset to PENDING");
            assertTrue(worker.recoverInterrupted().isEmpty(),
                    "a second recovery pass must not reset an already PENDING row");
            return ids;
        });

        assertTrue(requeued != null && requeued.contains(documentId),
                "recoverInterrupted must return the ids it re-queued, got " + requeued);
    }

    @Test
    void recoveryDoesNotTouchTerminalDocuments() throws Exception {
        long kbId = createKb();
        long ready = insertDocument(kbId, "READY");
        long failed = insertDocument(kbId, "FAILED");
        long deleted = insertDocument(kbId, "DELETED");

        List<Long> requeued = worker.recoverInterrupted();

        assertFalse(requeued.contains(ready), "READY must not be re-queued");
        assertFalse(requeued.contains(failed), "FAILED must not be re-queued");
        assertFalse(requeued.contains(deleted), "DELETED must not be re-queued");
        assertEquals("READY", statusOf(ready));
        assertEquals("FAILED", statusOf(failed));
        assertEquals("DELETED", statusOf(deleted));
    }

    @Test
    void recoveredDocumentEventuallyBecomesReady() throws Exception {
        long kbId = createKb();
        long documentId = insertDocument(kbId, "EMBEDDING");

        assertTrue(worker.recoverInterrupted().contains(documentId),
                "EMBEDDING is an intermediate status and must be recovered");
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "recovered document must finish ingesting");
        assertEquals("READY", statusOf(documentId));
    }

    private long insertDocument(long kbId, String status) {
        String name = "recovery-" + UUID.randomUUID() + ".txt";
        String objectKey = "kb/" + kbId + "/" + UUID.randomUUID() + "/" + name;
        // Since Task 7 the pipeline parses the stored object, so a recovered document can only
        // reach READY when its upload actually exists in MinIO.
        storageService.putText(objectKey, "recovery payload for " + name);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO documents (kb_id, filename, object_key, size, status)
                VALUES (?, ?, ?, 0, ?)
                RETURNING id
                """, Long.class, kbId, name, objectKey, status);
        if (id == null) {
            throw new IllegalStateException("insert did not return an id");
        }
        return id;
    }

    private String statusOf(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId);
    }

    private User createUser() {
        User user = new User("recovery-" + UUID.randomUUID(), passwordEncoder.encode(PASSWORD), UserRole.ADMIN);
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
                        .content("{\"name\":\"recovery-kb-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        // Since Task 7 the ingest pipeline really embeds, so a KB whose documents must reach READY
        // needs an embedding model.
        embeddingFixture.wire(id);
        createdKbIds.add(id);
        return id;
    }
}
