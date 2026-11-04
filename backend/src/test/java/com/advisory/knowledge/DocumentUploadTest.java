package com.advisory.knowledge;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.knowledge.api.dto.CreateDocumentRequest;
import com.advisory.knowledge.service.DocumentIngestWorker;
import com.advisory.knowledge.service.DocumentService;
import com.advisory.knowledge.service.StorageService;
import com.advisory.provider.service.CryptoService;
import com.advisory.support.EmbeddingModelFixture;
import com.advisory.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档上传全流程：预签名 PUT 在 MinIO 测试容器上确实可用；create 以 {@code objectKey} 幂等；
 * 知识库与节点的存在性校验；真实入库流水线能把文档推到 {@code READY}；软删除后读接口不再返回该文档；
 * 写操作的「仅管理员」矩阵；以及重试的前置条件保护。
 */
class DocumentUploadTest extends IntegrationTestBase {

    private static final String PASSWORD = "doc-upload-pass-1";
    private static final long FIFTY_MB = 50L * 1024 * 1024;
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
    private DocumentService documentService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String adminToken;
    private String userToken;
    private long adminUserId;
    private final List<Long> createdKbIds = new ArrayList<>();
    private EmbeddingModelFixture embeddingFixture;

    @BeforeEach
    void createFixtures() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);
        adminUserId = admin.getId();
        adminToken = login(admin.getUsername());
        userToken = login(user.getUsername());
        embeddingFixture = EmbeddingModelFixture.create(jdbcTemplate, cryptoService);
    }

    @AfterEach
    void cleanup() {
        // 先等正在跑的流水线结束，再把它的数据从底下抽走。
        worker.awaitIdle(WORKER_IDLE_TIMEOUT);
        for (Long kbId : createdKbIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE kb_id = ?", kbId);
            jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        }
        createdKbIds.clear();
        if (embeddingFixture != null) {
            // 引用该模型的知识库已在上面删除（外键是 ON DELETE RESTRICT）。
            embeddingFixture.close();
            embeddingFixture = null;
        }
    }

    @Test
    void presignThenCreateIsIdempotent() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        byte[] content = "hello advisory".getBytes(StandardCharsets.UTF_8);

        Presigned presigned = presign(kbId, nodeId, "hello.txt", content.length);
        assertEquals(600, presigned.expiresInSeconds(), "presigned URL TTL must be 10 minutes");
        assertTrue(presigned.uploadUrl().contains("X-Amz-Expires=600"),
                "the signed URL itself must carry the 10-minute expiry: " + presigned.uploadUrl());
        assertTrue(presigned.objectKey().startsWith("kb/" + kbId + "/"),
                "object key must be scoped to the knowledge base: " + presigned.objectKey());
        assertTrue(presigned.objectKey().endsWith("/hello.txt"), "object key keeps the filename");

        putBytes(presigned.uploadUrl(), content);

        long first = createDocument(kbId, nodeId, "hello.txt", presigned.objectKey(), content.length, 201);
        long replay = createDocument(kbId, nodeId, "hello.txt", presigned.objectKey(), content.length, 200);

        assertEquals(first, replay, "replaying create must return the existing document");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE object_key = ?", Integer.class, presigned.objectKey());
        assertEquals(1, rows, "object_key must be idempotent");
    }

    @Test
    void createWithoutUploadedObjectRejected() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");

        Presigned presigned = presign(kbId, nodeId, "never-uploaded.txt", 12);

        mockMvc.perform(post("/api/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(kbId, nodeId, "never-uploaded.txt", presigned.objectKey(), 12)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void nodeFromAnotherKbRejected() throws Exception {
        long kbId = createKb();
        long otherKbId = createKb();
        long foreignNodeId = createNode(otherKbId, "elsewhere");

        mockMvc.perform(post("/api/documents/presign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presignBody(kbId, foreignNodeId, "x.txt", 10)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void pipelineReachesReady() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        byte[] content = "pipeline body".getBytes(StandardCharsets.UTF_8);

        Presigned presigned = presign(kbId, nodeId, "pipeline.txt", content.length);
        putBytes(presigned.uploadUrl(), content);
        long documentId = createDocument(kbId, nodeId, "pipeline.txt", presigned.objectKey(), content.length, 201);

        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "ingest pipeline must become idle");

        mockMvc.perform(get("/api/documents/" + documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.filename").value("pipeline.txt"));

        Integer chunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertTrue(chunks != null && chunks >= 1,
                "Task 7 makes the pipeline real: the parsed document must be chunked and embedded");
    }

    @Test
    void deleteSoftDeletesAndHidesFromList() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        byte[] content = "to be deleted".getBytes(StandardCharsets.UTF_8);

        Presigned presigned = presign(kbId, nodeId, "doomed.txt", content.length);
        putBytes(presigned.uploadUrl(), content);
        long documentId = createDocument(kbId, nodeId, "doomed.txt", presigned.objectKey(), content.length, 201);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT));
        jdbcTemplate.update("""
                INSERT INTO chunks (document_id, kb_id, node_id, seq, content)
                VALUES (?, ?, ?, 0, 'leftover chunk')
                """, documentId, kbId, nodeId);

        mockMvc.perform(delete("/api/documents/" + documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/documents/" + documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        String listBody = mockMvc.perform(get("/api/documents")
                        .param("kbId", String.valueOf(kbId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> content2 = JsonPath.read(listBody, "$.data.content");
        assertTrue(content2.stream().noneMatch(item -> idOf(item) == documentId),
                "deleted documents must not be listed");
        assertEquals("DELETED", jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId),
                "row must be soft-deleted, not removed");

        // 清理跑在 worker 线程池上：用有界等待 awaitIdle，而不是固定睡眠。
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "delete cleanup must finish");
        Integer leftoverChunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        assertEquals(0, leftoverChunks, "soft delete must remove the document's chunks");
        assertThrows(Exception.class, () -> minioClient().statObject(StatObjectArgs.builder()
                        .bucket(MINIO_BUCKET)
                        .object(presigned.objectKey())
                        .build()),
                "soft delete must remove the MinIO object");
    }

    @Test
    void userCannotUpload() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");

        mockMvc.perform(post("/api/documents/presign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presignBody(kbId, nodeId, "denied.txt", 10)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void retryOnlyFromFailed() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        byte[] content = "ready already".getBytes(StandardCharsets.UTF_8);

        Presigned presigned = presign(kbId, nodeId, "ready.txt", content.length);
        putBytes(presigned.uploadUrl(), content);
        long documentId = createDocument(kbId, nodeId, "ready.txt", presigned.objectKey(), content.length, 201);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT));

        mockMvc.perform(post("/api/documents/" + documentId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        assertEquals("READY", jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId));
    }

    @Test
    void oversizedDocumentRejected() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");

        mockMvc.perform(post("/api/documents/presign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presignBody(kbId, nodeId, "huge.bin", FIFTY_MB + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void objectLargerThanLimitRejectedFromActualSize() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        byte[] oversized = new byte[(int) FIFTY_MB + 1];

        // 客户端谎报了大小（说 10 字节）；只有对已存对象做 stat 才能戳穿。
        Presigned presigned = presign(kbId, nodeId, "big.bin", 10);
        putBytes(presigned.uploadUrl(), oversized);

        mockMvc.perform(post("/api/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(kbId, nodeId, "big.bin", presigned.objectKey(), 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE object_key = ?", Integer.class, presigned.objectKey()),
                "an oversized object must not create a document row");
    }

    @Test
    void duplicateObjectKeyIsRejectedByTheDatabase() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        String objectKey = "kb/" + kbId + "/" + UUID.randomUUID() + "/duplicate.txt";

        insertDocumentRow(kbId, nodeId, "duplicate.txt", objectKey, 3);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertDocumentRow(kbId, nodeId, "duplicate.txt", objectKey, 3),
                "V2 must provide a unique index on documents.object_key");
    }

    /**
     * 同时验证「唯一索引兜底」与「重放回退」：主线程先持有一条未提交、但已占用该键的行
     * （于是并发 create 的查询看不到它），并发 create 会阻塞在唯一索引上；主线程提交后它拿到 23505，
     * 必须改而返回成功方那一行，而不是插入一条重复记录。
     */
    @Test
    void concurrentCreateReusesTheWinningRow() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        byte[] content = "race".getBytes(StandardCharsets.UTF_8);
        Presigned presigned = presign(kbId, nodeId, "race.txt", content.length);
        putBytes(presigned.uploadUrl(), content);

        CreateDocumentRequest request = new CreateDocumentRequest(
                kbId, nodeId, "race.txt", presigned.objectKey(), (long) content.length, null);
        AtomicLong heldId = new AtomicLong();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Future<DocumentService.Creation> racing = tx.execute(status -> {
                heldId.set(insertDocumentRow(kbId, nodeId, "race.txt", presigned.objectKey(), content.length));
                Future<DocumentService.Creation> future =
                        pool.submit(() -> documentService.create(request, adminUserId));
                awaitBackendWaitingOnALock();
                return future;
            });

            DocumentService.Creation creation = racing.get(30, TimeUnit.SECONDS);
            assertFalse(creation.created(), "the loser must report a replay, not a fresh insert");
            assertEquals(heldId.get(), creation.document().id(),
                    "the loser must return the winner's row");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE object_key = ?", Integer.class, presigned.objectKey()),
                "exactly one row may exist for an object_key");
    }

    /**
     * 不引入生产代码里的测试钩子，也要造出 FAILED 路径：临时建一个触发器，让
     * {@code PENDING -> PARSING} 的比较并设置失败，worker 捕获后写入 FAILED；随后删掉触发器，
     * 让重试把文档推到 READY。
     */
    @Test
    void failedDocumentCanBeRetried() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        String objectKey = "kb/" + kbId + "/" + UUID.randomUUID() + "/failing.txt";
        long documentId = insertDocumentRow(kbId, nodeId, "failing.txt", objectKey, 3);
        // 自 Task 7 起，重试会真的去解析已存对象，因此 MinIO 里必须有这个上传对象，重试才可能到 READY。
        storageService.putText(objectKey, "retry body after the simulated failure");

        String function = "fail_ingest_" + UUID.randomUUID().toString().replace("-", "");
        String trigger = "trg_" + function;
        try {
            jdbcTemplate.execute("CREATE FUNCTION " + function
                    + "() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'simulated ingest failure'; END; $$ LANGUAGE plpgsql");
            jdbcTemplate.execute("CREATE TRIGGER " + trigger
                    + " BEFORE UPDATE ON documents FOR EACH ROW WHEN (OLD.id = " + documentId
                    + " AND NEW.status = 'PARSING') EXECUTE FUNCTION " + function + "()");

            worker.enqueue(documentId);
            assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "failing ingest must finish");

            mockMvc.perform(get("/api/documents/" + documentId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("FAILED"))
                    .andExpect(jsonPath("$.data.errorMsg").isNotEmpty());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON documents");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }

        mockMvc.perform(post("/api/documents/" + documentId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.errorMsg").doesNotExist());

        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "retried ingest must finish");
        assertEquals("READY", jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT error_msg FROM documents WHERE id = ?", String.class, documentId),
                "retry must clear error_msg");
    }

    private long insertDocumentRow(long kbId, Long nodeId, String filename, String objectKey, long size) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO documents (kb_id, node_id, filename, object_key, size, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                RETURNING id
                """, Long.class, kbId, nodeId, filename, objectKey, size);
        if (id == null) {
            throw new IllegalStateException("insert did not return an id");
        }
        return id;
    }

    /** 等到另一个连接确实被行锁或索引锁挡住，也就是并发插入已经排进队列。 */
    private void awaitBackendWaitingOnALock() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for the racing insert to block");
            }
        }
        fail("the concurrent create never blocked on the unique index");
    }

    private Presigned presign(long kbId, Long nodeId, String filename, long size) throws Exception {
        String body = mockMvc.perform(post("/api/documents/presign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presignBody(kbId, nodeId, filename, size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Presigned(
                JsonPath.read(body, "$.data.uploadUrl"),
                JsonPath.read(body, "$.data.objectKey"),
                ((Number) JsonPath.read(body, "$.data.expiresInSeconds")).intValue());
    }

    private static void putBytes(String uploadUrl, byte[] content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode(), "presigned PUT must be accepted by MinIO");
    }

    private long createDocument(long kbId, Long nodeId, String filename, String objectKey, long size,
                                int expectedStatus) throws Exception {
        String body = mockMvc.perform(post("/api/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(kbId, nodeId, filename, objectKey, size)))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    private static String presignBody(long kbId, Long nodeId, String filename, long size) {
        return "{\"kbId\":" + kbId
                + ",\"nodeId\":" + nodeId
                + ",\"filename\":\"" + filename + "\""
                + ",\"size\":" + size + "}";
    }

    private static String createBody(long kbId, Long nodeId, String filename, String objectKey, long size) {
        return "{\"kbId\":" + kbId
                + ",\"nodeId\":" + nodeId
                + ",\"filename\":\"" + filename + "\""
                + ",\"objectKey\":\"" + objectKey + "\""
                + ",\"size\":" + size + "}";
    }

    private User createUser(UserRole role) {
        User user = new User("doc-" + UUID.randomUUID(), passwordEncoder.encode(PASSWORD), role);
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
                        .content("{\"name\":\"doc-kb-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        // 自 Task 7 起流水线会真的解析、切片、向量化，因此知识库必须配向量模型才能把文档推到 READY；
        // 没有模型时 worker 会让文档失败。
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

    private static long idOf(Map<String, Object> item) {
        return ((Number) item.get("id")).longValue();
    }

    private record Presigned(String uploadUrl, String objectKey, int expiresInSeconds) {
    }
}
