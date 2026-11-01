package com.advisory.knowledge;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.knowledge.service.DocumentIngestWorker;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Document upload flow: presigned PUT that really works against the MinIO test container, idempotent
 * create on {@code objectKey}, KB/node validation, the stub ingest pipeline reaching {@code READY},
 * soft delete hiding the document from reads, the ADMIN-only write matrix and retry guards.
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
    private DocumentIngestWorker worker;

    private String adminToken;
    private String userToken;
    private final List<Long> createdKbIds = new ArrayList<>();

    @BeforeEach
    void createFixtures() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);
        adminToken = login(admin.getUsername());
        userToken = login(user.getUsername());
    }

    @AfterEach
    void cleanup() {
        // Let any in-flight pipeline finish before rows disappear from under it.
        worker.awaitIdle(WORKER_IDLE_TIMEOUT);
        for (Long kbId : createdKbIds) {
            jdbcTemplate.update("DELETE FROM documents WHERE kb_id = ?", kbId);
            jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        }
        createdKbIds.clear();
    }

    @Test
    void presignThenCreateIsIdempotent() throws Exception {
        long kbId = createKb();
        long nodeId = createNode(kbId, "inbox");
        byte[] content = "hello advisory".getBytes(StandardCharsets.UTF_8);

        Presigned presigned = presign(kbId, nodeId, "hello.txt", content.length);
        assertEquals(600, presigned.expiresInSeconds(), "presigned URL TTL must be 10 minutes");
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
        assertEquals(0, chunks, "Task 5 stubs the pipeline: READY with no chunks");
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
