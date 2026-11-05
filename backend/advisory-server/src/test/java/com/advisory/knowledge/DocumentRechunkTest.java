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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重新裁切的集成测试：单篇与批量接口都只把文档排进裁切队列并立刻返回 {@code PENDING}；
 * 重新裁切复用已落库的解析产物（原始文件删掉也能跑通）；切片配置改动后按新配置重新切片；
 * 正在入库的文档、普通用户、不存在的 id 分别得到 409 / 403 / 跳过。
 */
class DocumentRechunkTest extends IntegrationTestBase {

    private static final String PASSWORD = "rechunk-pass-1";
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

    private String adminToken;
    private String userToken;
    private final List<Long> createdKbIds = new ArrayList<>();
    private EmbeddingModelFixture embeddingFixture;

    @BeforeEach
    void createFixtures() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);
        adminToken = login(admin.getUsername());
        userToken = login(user.getUsername());
        embeddingFixture = EmbeddingModelFixture.create(jdbcTemplate, cryptoService);
    }

    @AfterEach
    void cleanup() {
        worker.awaitIdle(WORKER_IDLE_TIMEOUT);
        for (Long kbId : createdKbIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE kb_id = ?", kbId);
            jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
            storageService.deleteBucketQuietly(kbId);
        }
        createdKbIds.clear();
        if (embeddingFixture != null) {
            embeddingFixture.close();
            embeddingFixture = null;
        }
    }

    /**
     * 复用解析产物的最硬证据：把原始对象删掉再重新裁切。
     *
     * <p>如果这条路又去解析原文件，流水线会以「存储中找不到已上传的文件对象」失败；它仍然到了
     * {@code READY}，且片段数与第一次完全一致，说明用的就是 {@code parsed.md}。</p>
     */
    @Test
    void rechunkReusesParsedTextAfterTheOriginalObjectIsGone() throws Exception {
        long kbId = createKb();
        long documentId = makeReadyDocument(kbId, "guide.md", "# 一级标题\n\n" + "知识库切片正文。".repeat(40));
        Integer chunksBefore = chunkCount(documentId);
        assertTrue(chunksBefore != null && chunksBefore >= 1, "首次入库必须落下片段");

        String objectKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM documents WHERE id = ?", String.class, documentId);
        storageService.remove(kbId, objectKey);

        mockMvc.perform(post("/api/documents/" + documentId + "/rechunk")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "重新裁切必须跑完");
        assertEquals("READY", statusOf(documentId),
                "原文件已删除仍能就绪，证明重新裁切复用了 parsed.md 而没有重新解析");
        assertEquals(chunksBefore, chunkCount(documentId), "同样的文本与配置必须切出同样数量的片段");
        assertNull(errorMsgOf(documentId));
    }

    /** 改完切片参数后，单篇重新裁切必须按新配置重切，而不需要整库重建。 */
    @Test
    void rechunkAppliesTheLatestChunkConfig() throws Exception {
        long kbId = createKb();
        long documentId = makeReadyDocument(kbId, "long.md", "x".repeat(1200));
        Integer chunksBefore = chunkCount(documentId);
        assertTrue(chunksBefore != null && chunksBefore <= 2,
                "默认配置下 1200 字应当只有一两段，实际 " + chunksBefore);

        jdbcTemplate.update("""
                UPDATE knowledge_bases SET chunk_config = '{"minChars": 50, "maxChars": 120, "overlapRatio": 0.1}'::jsonb
                WHERE id = ?
                """, kbId);

        mockMvc.perform(post("/api/documents/" + documentId + "/rechunk")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT));

        Integer chunksAfter = chunkCount(documentId);
        assertTrue(chunksAfter != null && chunksAfter > chunksBefore,
                "更小的 maxChars 必须切出更多片段，改前 " + chunksBefore + "，改后 " + chunksAfter);
    }

    /** 批量：去重后按提交顺序排队，不存在的 id 只被跳过，不影响其它文档。 */
    @Test
    void batchRechunkQueuesEveryDocumentAndSkipsUnknownOnes() throws Exception {
        long kbId = createKb();
        long first = makeReadyDocument(kbId, "a.md", "第一篇正文内容。".repeat(20));
        long second = makeReadyDocument(kbId, "b.md", "第二篇正文内容。".repeat(20));
        long unknown = 987_654_321L;

        String body = mockMvc.perform(post("/api/documents/rechunk")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIds\":[" + first + "," + second + "," + unknown + "," + first + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requested").value(3))
                .andExpect(jsonPath("$.data.accepted").value(2))
                .andExpect(jsonPath("$.data.skipped.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Map<String, Object>> skipped = JsonPath.read(body, "$.data.skipped");
        assertEquals(((Number) skipped.get(0).get("id")).longValue(), unknown, "被跳过的必须是不存在的那个 id");
        assertTrue(String.valueOf(skipped.get(0).get("reason")).contains("不存在"),
                "跳过原因要能直接展示给用户，实际：" + skipped.get(0).get("reason"));

        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "两篇文档都必须跑完");
        assertEquals("READY", statusOf(first));
        assertEquals("READY", statusOf(second));
    }

    /** 已在队列里（PENDING）的文档再点一次重新裁切是无效操作，应当明确冲突。 */
    @Test
    void rechunkWhileIngestingConflicts() throws Exception {
        long kbId = createKb();
        long documentId = insertPendingDocument(kbId, "pending.md", "还没开始处理的内容");

        mockMvc.perform(post("/api/documents/" + documentId + "/rechunk")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        assertEquals("PENDING", statusOf(documentId), "被拒绝的请求不能改动状态");
    }

    @Test
    void userCannotRechunk() throws Exception {
        long kbId = createKb();
        long documentId = makeReadyDocument(kbId, "guarded.md", "普通用户不能重新裁切");

        mockMvc.perform(post("/api/documents/" + documentId + "/rechunk")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(post("/api/documents/rechunk")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIds\":[" + documentId + "]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    // ------------------------------------------------------------------ 辅助方法

    /** 造一篇 READY 文档：内容先写进 MinIO，再插 PENDING 行并入队，等流水线跑完。 */
    private long makeReadyDocument(long kbId, String filename, String content) {
        long documentId = insertPendingDocument(kbId, filename, content);
        worker.enqueue(documentId);
        assertTrue(worker.awaitIdle(WORKER_IDLE_TIMEOUT), "首次入库必须跑完");
        assertEquals("READY", statusOf(documentId), "夹具文档必须入库成功");
        return documentId;
    }

    private long insertPendingDocument(long kbId, String filename, String content) {
        String objectKey = "kb/" + kbId + "/" + UUID.randomUUID() + "/" + filename;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageService.putText(kbId, objectKey, content);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO documents (kb_id, filename, object_key, size, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                RETURNING id
                """, Long.class, kbId, filename, objectKey, (long) bytes.length);
        if (id == null) {
            throw new IllegalStateException("insert did not return an id");
        }
        return id;
    }

    private String statusOf(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId);
    }

    private String errorMsgOf(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT error_msg FROM documents WHERE id = ?", String.class, documentId);
    }

    private Integer chunkCount(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
    }

    private long createKb() throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rechunk-kb-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        embeddingFixture.wire(id);
        createdKbIds.add(id);
        return id;
    }

    private User createUser(UserRole role) {
        User user = new User("rechunk-" + UUID.randomUUID(), passwordEncoder.encode(PASSWORD), role);
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
}
