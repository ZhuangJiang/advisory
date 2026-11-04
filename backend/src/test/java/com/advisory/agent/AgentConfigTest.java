package com.advisory.agent;

import com.advisory.agent.service.AgentService;
import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.knowledge.repo.KbNodeJdbcRepository;
import com.advisory.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 10: agent configuration, automatic versioning and cross-knowledge-base validation.
 *
 * <p>Every row this class creates is prefixed {@code agent10-} and removed in {@code @AfterEach}
 * (conversations, then agents — their versions cascade — then knowledge bases/nodes, then the
 * provider whose models cascade). Agents and knowledge bases hold {@code ON DELETE RESTRICT}
 * references to {@code models}, so the delete order matters.</p>
 */
class AgentConfigTest extends IntegrationTestBase {

    private static final String PASSWORD = "agent-pass-1";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KbNodeJdbcRepository nodes;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentService agentService;

    private long providerId;
    private long chatModelId;
    private long firstEmbeddingModelId;
    private long secondEmbeddingModelId;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        providerId = jdbc.queryForObject(
                "INSERT INTO model_providers (name, type, base_url) VALUES (?, 'OLLAMA', ?) RETURNING id",
                Long.class, "agent10-prov-" + UUID.randomUUID(), "http://127.0.0.1:9");
        chatModelId = createModel("CHAT", null);
        firstEmbeddingModelId = createModel("EMBEDDING", 1024);
        secondEmbeddingModelId = createModel("EMBEDDING", 1024);

        adminToken = login(createUser(UserRole.ADMIN));
        userToken = login(createUser(UserRole.USER));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("""
                DELETE FROM conversations
                WHERE agent_id IN (SELECT id FROM agents WHERE name LIKE 'agent10-%')
                """);
        jdbc.update("DELETE FROM agents WHERE name LIKE 'agent10-%'");
        jdbc.update("""
                DELETE FROM kb_nodes
                WHERE kb_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'agent10-kb-%')
                """);
        jdbc.update("DELETE FROM knowledge_bases WHERE name LIKE 'agent10-kb-%'");
        jdbc.update("DELETE FROM model_providers WHERE name LIKE 'agent10-prov-%'");
    }

    // ------------------------------------------------------------------ 1. versions

    /**
     * Creation is version 1 (a conversation created before the first edit already points at version
     * 1, so that snapshot must exist) and every successful PATCH appends exactly one version.
     */
    @Test
    void everyUpdateCreatesNewVersion() throws Exception {
        long kbId = createKb(firstEmbeddingModelId);
        long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(),
                "prompt v1");

        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(1));
        assertEquals(List.of(1), versions(agentId));

        patchAgent(agentId, patchBody(Map.of("systemPrompt", "prompt v2")));
        patchAgent(agentId, patchBody(Map.of("topK", 30)));

        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(3))
                .andExpect(jsonPath("$.data.topK").value(30));
        assertEquals(List.of(3, 2, 1), versions(agentId), "newest first, one row per PATCH");
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM agent_versions WHERE agent_id = ?", Integer.class, agentId));
    }

    // ------------------------------------------------------------------ 2. cross-kb checks

    @Test
    void rejectsMixedEmbeddingModelsAcrossKbs() throws Exception {
        long firstKb = createKb(firstEmbeddingModelId);
        long secondKb = createKb(secondEmbeddingModelId);

        String body = createBody("agent10-a-" + UUID.randomUUID(), List.of(firstKb, secondKb), Map.of(),
                "prompt");
        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE name LIKE 'agent10-a-%'", Integer.class),
                "a rejected agent must not be inserted");

        // a second knowledge base with the *same* embedding model is accepted
        long sameModelKb = createKb(firstEmbeddingModelId);
        long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(firstKb, sameModelKb),
                Map.of(), "prompt");
        assertEquals(firstEmbeddingModelId, jdbc.queryForObject(
                "SELECT embedding_model_id FROM agents WHERE id = ?", Long.class, agentId),
                "the embedding model is derived from the knowledge bases, not sent by the client");

        // a PATCH that would mix models is rejected and leaves the stored config untouched
        String patch = patchBody(Map.of("kbIds", List.of(firstKb, secondKb)));
        mockMvc.perform(patch("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.currentVersion").value(1))
                .andExpect(jsonPath("$.data.kbIds.length()").value(2))
                .andExpect(jsonPath("$.data.embeddingModelId").value(firstEmbeddingModelId));
    }

    @Test
    void rejectsNodeFromAnotherKb() throws Exception {
        long kbId = createKb(firstEmbeddingModelId);
        long otherKbId = createKb(firstEmbeddingModelId);
        long ownNode = nodes.insert(kbId, null, null, "own");
        long foreignNode = nodes.insert(otherKbId, null, null, "foreign");

        String body = createBody("agent10-a-" + UUID.randomUUID(), List.of(kbId),
                Map.of(String.valueOf(kbId), List.of(foreignNode)), "prompt");
        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // a node of the declared knowledge base is accepted
        long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId),
                Map.of(String.valueOf(kbId), List.of(ownNode)), "prompt");
        String listed = mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, List<Integer>> scope = JsonPath.read(listed, "$.data.nodeScope");
        assertEquals(List.of((int) ownNode), scope.get(String.valueOf(kbId)));

        // a scope key that is not one of the agent's knowledge bases is rejected too
        String strayScope = createBody("agent10-a-" + UUID.randomUUID(), List.of(kbId),
                Map.of(String.valueOf(otherKbId), List.of(foreignNode)), "prompt");
        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(strayScope))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // ... and so is a node id that does not exist at all
        String ghostScope = createBody("agent10-a-" + UUID.randomUUID(), List.of(kbId),
                Map.of(String.valueOf(kbId), List.of(999_999_999L)), "prompt");
        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ghostScope))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    // ------------------------------------------------------------------ 3. snapshot immutability

    @Test
    void versionSnapshotIsImmutable() throws Exception {
        long kbId = createKb(firstEmbeddingModelId);
        long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(),
                "prompt v1");

        String v1Json = snapshotJson(agentId, 1);
        patchAgent(agentId, patchBody(Map.of("systemPrompt", "prompt v2")));
        patchAgent(agentId, patchBody(Map.of("systemPrompt", "prompt v3")));

        assertEquals(v1Json, snapshotJson(agentId, 1), "the version-1 snapshot must not be rewritten");
        assertTrue(v1Json.contains("prompt v1"));
        assertTrue(snapshotJson(agentId, 2).contains("prompt v2"));
        assertTrue(snapshotJson(agentId, 3).contains("prompt v3"));

        String listed = mockMvc.perform(get("/api/agents/" + agentId + "/versions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertEquals(List.of(3, 2, 1), JsonPath.read(listed, "$.data[*].version"));
        assertEquals("prompt v1", JsonPath.read(listed, "$.data[2].snapshot.systemPrompt"));

        // the contract Task 11 consumes: a historical version still resolves to its own config
        assertEquals("prompt v1", agentService.resolveSnapshot(agentId, 1).systemPrompt());
        assertEquals("prompt v3", agentService.resolveSnapshot(agentId, 3).systemPrompt());
        assertEquals(3, agentService.resolveSnapshot(agentId, 3).maxRounds(),
                "the snapshot carries the default maxRounds = 3");
    }

    // ------------------------------------------------------------------ 4. validation ranges

    @Test
    void rejectsInvalidConfigValues() throws Exception {
        long kbId = createKb(firstEmbeddingModelId);

        assertCreateRejected(kbId, Map.of("maxRounds", 0));
        assertCreateRejected(kbId, Map.of("maxRounds", 7));
        assertCreateRejected(kbId, Map.of("topK", 0));
        assertCreateRejected(kbId, Map.of("topK", 101));
        assertCreateRejected(kbId, Map.of("temperature", new BigDecimal("2.5")));
        assertCreateRejected(kbId, Map.of("temperature", new BigDecimal("-0.1")));
        assertCreateRejected(kbId, Map.of("systemPrompt", "   "));
        assertCreateRejected(kbId, Map.of("kbIds", List.of()));
        assertCreateRejected(kbId, Map.of("chatModelId", 999_999_999L));
        assertCreateRejected(kbId, Map.of("chatModelId", firstEmbeddingModelId));
        assertCreateRejected(kbId, Map.of("kbIds", List.of(999_999_999L)));

        // a knowledge base without an embedding model can never be retrieved from
        long noModelKb = jdbc.queryForObject(
                "INSERT INTO knowledge_bases (name) VALUES (?) RETURNING id", Long.class,
                "agent10-kb-" + UUID.randomUUID());
        assertCreateRejected(noModelKb, Map.of());
    }

    // ------------------------------------------------------------------ 5. deletion guard

    @Test
    void deleteIsBlockedWhileConversationsExist() throws Exception {
        long kbId = createKb(firstEmbeddingModelId);
        long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");

        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username LIKE 'agent10-user-%' LIMIT 1", Long.class);
        long conversationId = jdbc.queryForObject("""
                INSERT INTO conversations (agent_id, agent_version, user_id, title)
                VALUES (?, 1, ?, 'history') RETURNING id
                """, Long.class, agentId, userId);

        mockMvc.perform(delete("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
        assertNotNullAgent(agentId);

        jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);
        mockMvc.perform(delete("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE id = ?", Integer.class, agentId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM agent_versions WHERE agent_id = ?", Integer.class, agentId),
                "versions cascade with the agent");
    }

    // ------------------------------------------------------------------ 6. duplicate names

    @Test
    void duplicateAgentNameConflicts() throws Exception {
        long kbId = createKb(firstEmbeddingModelId);
        String name = "agent10-a-" + UUID.randomUUID();
        createAgent(name, List.of(kbId), Map.of(), "prompt");

        String duplicate = createBody(name, List.of(kbId), Map.of(), "other prompt");
        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    // ------------------------------------------------------------------ 7. permissions

    @Test
    void userCanReadAgentsButCannotWriteThem() throws Exception {
        long kbId = createKb(firstEmbeddingModelId);
        long agentId = createAgent("agent10-a-" + UUID.randomUUID(), List.of(kbId), Map.of(), "prompt");

        mockMvc.perform(get("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").exists());

        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("agent10-nope-" + UUID.randomUUID(), List.of(kbId),
                                Map.of(), "prompt")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(patch("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(Map.of("systemPrompt", "hijacked"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(delete("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get("/api/agents/" + agentId + "/versions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // nothing above changed the agent
        assertEquals(1, versions(agentId).size());
    }

    // ------------------------------------------------------------------ helpers

    private void assertCreateRejected(long kbId, Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "agent10-a-" + UUID.randomUUID());
        body.put("systemPrompt", "prompt");
        body.put("chatModelId", chatModelId);
        body.put("kbIds", List.of(kbId));
        body.put("nodeScope", Map.of());
        body.putAll(overrides);
        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    private long createAgent(String name, List<Long> kbIds, Map<String, List<Long>> nodeScope,
                             String systemPrompt) throws Exception {
        String response = mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, kbIds, nodeScope, systemPrompt)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private String createBody(String name, List<Long> kbIds, Map<String, List<Long>> nodeScope,
                              String systemPrompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "task 10 fixture");
        body.put("systemPrompt", systemPrompt);
        body.put("chatModelId", chatModelId);
        body.put("kbIds", kbIds);
        body.put("nodeScope", nodeScope);
        return objectMapper.writeValueAsString(body);
    }

    private String patchBody(Map<String, Object> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    private void patchAgent(long agentId, String body) throws Exception {
        mockMvc.perform(patch("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private List<Integer> versions(long agentId) throws Exception {
        String listed = mockMvc.perform(get("/api/agents/" + agentId + "/versions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(listed, "$.data[*].version");
    }

    private String snapshotJson(long agentId, int version) {
        return jdbc.queryForObject(
                "SELECT snapshot::text FROM agent_versions WHERE agent_id = ? AND version = ?",
                String.class, agentId, version);
    }

    private void assertNotNullAgent(long agentId) {
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE id = ?", Integer.class, agentId));
    }

    private long createModel(String capability, Integer dimension) {
        String name = "agent10-model-" + UUID.randomUUID();
        if (dimension == null) {
            return jdbc.queryForObject(
                    "INSERT INTO models (provider_id, model_name, capability) VALUES (?, ?, ?) RETURNING id",
                    Long.class, providerId, name, capability);
        }
        return jdbc.queryForObject(
                "INSERT INTO models (provider_id, model_name, capability, dimension) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, providerId, name, capability, dimension);
    }

    private long createKb(long embeddingModelId) {
        return jdbc.queryForObject(
                "INSERT INTO knowledge_bases (name, embedding_model_id) VALUES (?, ?) RETURNING id",
                Long.class, "agent10-kb-" + UUID.randomUUID(), embeddingModelId);
    }

    private User createUser(UserRole role) {
        User user = new User("agent10-user-" + UUID.randomUUID(), passwordEncoder.encode(PASSWORD), role);
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
}
