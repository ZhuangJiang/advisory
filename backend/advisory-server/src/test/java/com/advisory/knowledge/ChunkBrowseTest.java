package com.advisory.knowledge;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/documents/{id}/chunks}——知识库分块抽屉所依赖的读接口。
 *
 * <p>数据行直接插入而不走入库链路：本用例考的是读接口的契约（顺序、分页、权限、404 语义），
 * 入库链路已由 {@link EmbeddingWriteTest} 覆盖，那里同时也验证了标题路径会被持久化。</p>
 */
class ChunkBrowseTest extends IntegrationTestBase {

    private static final String PASSWORD = "chunk-browse-pass-1";
    private static final String PREFIX = "chunk-browse-";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String userToken;
    private long kbId;
    private long documentId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = login(createUser(UserRole.ADMIN));
        userToken = login(createUser(UserRole.USER));

        kbId = jdbc.queryForObject(
                "INSERT INTO knowledge_bases (name) VALUES (?) RETURNING id",
                Long.class, PREFIX + UUID.randomUUID());
        documentId = insertDocument();
        insertChunk(0, "第一章 > 1.1 背景", "alpha");
        insertChunk(1, "第一章 > 1.2 目标", "beta");
        insertChunk(2, "", "gamma");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM documents WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
    }

    @Test
    void listsChunksInDocumentOrderWithHeadingPaths() throws Exception {
        String body = mockMvc.perform(get("/api/documents/" + documentId + "/chunks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(3))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> chunks = JsonPath.read(body, "$.data.content");
        assertEquals(List.of(0, 1, 2), chunks.stream().map(c -> ((Number) c.get("seq")).intValue()).toList(),
                "chunks must come back in seq order, not insertion or id order");
        assertEquals(List.of("alpha", "beta", "gamma"),
                chunks.stream().map(c -> (String) c.get("content")).toList());
        assertEquals("第一章 > 1.1 背景", chunks.get(0).get("headingPath"));
        assertEquals("", chunks.get(2).get("headingPath"),
                "a chunk without a heading reports an empty path, never null");
        assertTrue(chunks.get(0).get("id") instanceof Number);
    }

    @Test
    void paginatesWithoutLosingTheTotal() throws Exception {
        String body = mockMvc.perform(get("/api/documents/" + documentId + "/chunks")
                        .param("page", "1")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(1, (int) JsonPath.read(body, "$.data.page"));
        assertEquals(2, (int) JsonPath.read(body, "$.data.size"));
        assertEquals(3, (int) JsonPath.read(body, "$.data.total"));
        assertEquals(2, (int) JsonPath.read(body, "$.data.totalPages"));
        assertEquals(1, ((List<?>) JsonPath.read(body, "$.data.content")).size());
        assertEquals("gamma", JsonPath.read(body, "$.data.content[0].content"));
    }

    @Test
    void userRoleCanReadButAnUnknownDocumentIs404() throws Exception {
        mockMvc.perform(get("/api/documents/" + documentId + "/chunks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));

        mockMvc.perform(get("/api/documents/99999999/chunks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void softDeletedDocumentHidesItsChunks() throws Exception {
        jdbc.update("UPDATE documents SET status = 'DELETED' WHERE id = ?", documentId);

        mockMvc.perform(get("/api/documents/" + documentId + "/chunks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 辅助方法

    private User createUser(UserRole role) {
        User user = new User(PREFIX + UUID.randomUUID(), passwordEncoder.encode(PASSWORD), role);
        user.setEnabled(true);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private String login(User user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }

    private long insertDocument() {
        return jdbc.queryForObject("""
                        INSERT INTO documents (kb_id, filename, object_key, mime, size, status)
                        VALUES (?, ?, ?, 'text/plain', 15, 'READY')
                        RETURNING id
                        """,
                Long.class, kbId, PREFIX + "doc.txt", PREFIX + UUID.randomUUID() + "/doc.txt");
    }

    private void insertChunk(int seq, String headingPath, String content) {
        jdbc.update("""
                        INSERT INTO chunks (document_id, kb_id, seq, heading_path, content, token_count)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                documentId, kbId, seq, headingPath, content, 1);
    }
}
