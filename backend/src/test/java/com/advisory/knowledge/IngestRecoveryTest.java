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
 * 重启恢复：上一个进程遗留在中间状态（intermediate）的文档会被重置为 {@code PENDING} 并重新入队，
 * 已到终态的行不受影响，而恢复后的文档能够再次走到 {@code READY}。
 *
 * <p>{@code restartRequeuesInterruptedDocuments} 是在一个持有更新后行锁的事务里调用
 * {@code recoverInterrupted()} 的：它入队的 worker 任务会在自己的 CAS 上阻塞到本事务提交，
 * 因此那条 {@code PENDING} 断言是确定性的，而不会与流水线抢跑。</p>
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
            // 引用该模型的知识库已在上方删除（外键 ON DELETE RESTRICT）。
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
            // 此时批量 UPDATE ... RETURNING 仍持有行锁，它入队的任务在本事务提交前
            // 无法再推动该行前进。
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
        // 自 Task 7 起，流水线会解析已存储的对象，因此只有上传物确实存在于 MinIO 中，
        // 恢复后的文档才可能走到 READY。
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
        // 自 Task 7 起入库流水线会真正做向量化，所以要求文档走到 READY 的知识库
        // 必须配一个 embedding 模型。
        embeddingFixture.wire(id);
        createdKbIds.add(id);
        return id;
    }
}
