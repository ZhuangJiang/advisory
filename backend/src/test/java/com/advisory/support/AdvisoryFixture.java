package com.advisory.support;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.knowledge.retrieval.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * chat / agent / audit 三类集成用例共用的数据夹具：两个用户（ADMIN 与 USER）、
 * 一个带 CHAT 与 EMBEDDING 模型的提供商、一个知识库，以及一个版本号为 1 的 agent。
 *
 * <p>数据行的名称统一以 {@code fx-} 为前缀，每个用例结束后按依赖顺序清理：先会话，
 * 再 agent（其版本行随级联删除），再知识库，最后才是提供商（模型行随之级联）。
 * agent 与知识库对 {@code models} 持有 {@code ON DELETE RESTRICT} 外键，
 * 所以若先删提供商会直接失败。</p>
 *
 * <p>整个测试套件共用一个数据库（见 {@link IntegrationTestBase}），因此每一行夹具数据都带前缀，
 * 且逐用例清理；若某个用例还需要第二个用户或 agent，通过 {@link #createUser(UserRole)}
 * 或 agents 接口自行创建。</p>
 */
public abstract class AdvisoryFixture extends IntegrationTestBase {

    public static final String PASSWORD = "fixture-pass-1";

    private static final String PREFIX = "fx-";

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected long providerId;
    protected long chatModelId;
    protected long embeddingModelId;
    protected long kbId;
    protected long agentId;

    protected long adminId;
    protected long userId;
    protected String adminToken;
    protected String userToken;

    @BeforeEach
    void setUpFixture() throws Exception {
        providerId = jdbc.queryForObject(
                "INSERT INTO model_providers (name, type, base_url) VALUES (?, 'OLLAMA', ?) RETURNING id",
                Long.class, PREFIX + "prov-" + UUID.randomUUID(), "http://127.0.0.1:9");
        chatModelId = createModel("CHAT", null);
        embeddingModelId = createModel("EMBEDDING", 1024);
        kbId = jdbc.queryForObject(
                "INSERT INTO knowledge_bases (name, embedding_model_id) VALUES (?, ?) RETURNING id",
                Long.class, PREFIX + "kb-" + UUID.randomUUID(), embeddingModelId);

        User admin = createUser(UserRole.ADMIN);
        User user = createUser(UserRole.USER);
        adminId = admin.getId();
        userId = user.getId();
        adminToken = login(admin);
        userToken = login(user);
        agentId = createAgent();
    }

    @AfterEach
    void cleanupFixture() {
        jdbc.update("""
                DELETE FROM conversations
                WHERE agent_id IN (SELECT id FROM agents WHERE name LIKE 'fx-%')
                """);
        jdbc.update("DELETE FROM agents WHERE name LIKE 'fx-%'");
        jdbc.update("""
                DELETE FROM kb_nodes
                WHERE kb_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'fx-kb-%')
                """);
        jdbc.update("DELETE FROM knowledge_bases WHERE name LIKE 'fx-kb-%'");
        jdbc.update("DELETE FROM model_providers WHERE name LIKE 'fx-prov-%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'fx-user-%'");
    }

    // ------------------------------------------------------------------ 数据行

    protected long createConversation(String token) throws Exception {
        String response = mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":" + agentId + ",\"title\":\"fixture\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    /** 一条归属于任意「用户 + token」组合的会话，供审计过滤条件使用。 */
    protected long createConversation(String token, long agent) throws Exception {
        String response = mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":" + agent + ",\"title\":\"fixture\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    protected String sendMessage(long conversationId, String token, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        return mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
    }

    protected long insertAssistantMessage(long conversationId, String content) {
        return jdbc.queryForObject("""
                INSERT INTO messages (conversation_id, role, content, status)
                VALUES (?, 'ASSISTANT', ?, 'COMPLETED') RETURNING id
                """, Long.class, conversationId, content);
    }

    protected long assistantMessageId(long conversationId) {
        Long id = jdbc.queryForObject("""
                SELECT id FROM messages
                WHERE conversation_id = ? AND role = 'ASSISTANT'
                ORDER BY id DESC LIMIT 1
                """, Long.class, conversationId);
        return id == null ? -1 : id;
    }

    protected String messageStatus(long messageId) {
        return jdbc.queryForObject("SELECT status FROM messages WHERE id = ?", String.class, messageId);
    }

    protected long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    protected RetrievedChunk chunk(String content) {
        return new RetrievedChunk(101L, 202L, kbId, null, "handbook.pdf", null, content, 0.87);
    }

    protected long createAgent() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", PREFIX + "agent-" + UUID.randomUUID());
        body.put("systemPrompt", "你是内部政策助手，只依据给定片段回答。");
        body.put("chatModelId", chatModelId);
        body.put("kbIds", List.of(kbId));
        body.put("nodeScope", Map.of());
        String response = mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private long createModel(String capability, Integer dimension) {
        String name = PREFIX + "model-" + UUID.randomUUID();
        if (dimension == null) {
            return jdbc.queryForObject(
                    "INSERT INTO models (provider_id, model_name, capability) VALUES (?, ?, ?) RETURNING id",
                    Long.class, providerId, name, capability);
        }
        return jdbc.queryForObject(
                "INSERT INTO models (provider_id, model_name, capability, dimension) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, providerId, name, capability, dimension);
    }

    protected User createUser(UserRole role) {
        User user = new User(PREFIX + "user-" + UUID.randomUUID(),
                passwordEncoder.encode(PASSWORD), role);
        user.setEnabled(true);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    protected String login(User user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }
}
