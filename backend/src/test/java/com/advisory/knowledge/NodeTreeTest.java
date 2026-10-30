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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Knowledge base + node tree behaviour: ltree path rewriting on move, sibling-name uniqueness
 * (including the partial root index), non-empty deletion guard, cross-KB parent rejection and the
 * role matrix for the read/write split.
 */
class NodeTreeTest extends IntegrationTestBase {

    private static final String PASSWORD = "node-tree-pass-1";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String userToken;
    private final List<Long> createdKbIds = new ArrayList<>();

    @BeforeEach
    void createFixtures() throws Exception {
        User user = createUser(UserRole.USER);
        User admin = createUser(UserRole.ADMIN);
        userToken = login(user.getUsername());
        adminToken = login(admin.getUsername());
    }

    @AfterEach
    void cleanup() {
        for (Long kbId : createdKbIds) {
            jdbcTemplate.update("DELETE FROM agents WHERE kb_ids @> CAST(? AS jsonb)", "[" + kbId + "]");
            jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        }
        createdKbIds.clear();
    }

    @Test
    void moveRewritesSubtreePaths() throws Exception {
        long kbId = createKb();
        NodeData a = createNode(kbId, null, "A");
        NodeData b = createNode(kbId, a.id(), "B");
        NodeData c = createNode(kbId, b.id(), "C");

        mockMvc.perform(patch("/api/nodes/" + b.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null}"))
                .andExpect(status().isOk());

        Map<Long, String> paths = listNodePaths(kbId);
        assertEquals("n" + a.id(), paths.get(a.id()), "A's path must be unchanged");
        assertEquals("n" + b.id(), paths.get(b.id()), "B moves to root");
        assertEquals("n" + b.id() + ".n" + c.id(), paths.get(c.id()), "C follows B");
    }

    @Test
    void duplicateSiblingNameRejected() throws Exception {
        long kbId = createKb();
        NodeData parent = createNode(kbId, null, "parent");
        createNode(kbId, parent.id(), "child");

        mockMvc.perform(post("/api/knowledge-bases/" + kbId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + parent.id() + ",\"name\":\"child\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void duplicateRootNameRejected() throws Exception {
        long kbId = createKb();
        createNode(kbId, null, "root");

        mockMvc.perform(post("/api/knowledge-bases/" + kbId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"root\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void deleteNonEmptyNodeRejected() throws Exception {
        long kbId = createKb();
        NodeData a = createNode(kbId, null, "A");
        createNode(kbId, a.id(), "B");

        mockMvc.perform(delete("/api/nodes/" + a.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40902));
    }

    @Test
    void userCannotCreateKnowledgeBase() throws Exception {
        mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"user-kb\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void userCanReadNodes() throws Exception {
        long kbId = createKb();
        createNode(kbId, null, "A");

        mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void moveIntoOwnSubtreeRejected() throws Exception {
        long kbId = createKb();
        NodeData a = createNode(kbId, null, "A");
        NodeData b = createNode(kbId, a.id(), "B");
        NodeData c = createNode(kbId, b.id(), "C");

        mockMvc.perform(patch("/api/nodes/" + a.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + c.id() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void crossKbParentRejected() throws Exception {
        long kb1 = createKb();
        long kb2 = createKb();
        NodeData rootInKb1 = createNode(kb1, null, "root1");

        mockMvc.perform(post("/api/knowledge-bases/" + kb2 + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + rootInKb1.id() + ",\"name\":\"child\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void deleteKnowledgeBaseWithAgentReferenceRejected() throws Exception {
        long kbId = createKb();
        jdbcTemplate.update(
                "INSERT INTO agents (name, system_prompt, kb_ids) VALUES (?, ?, CAST(? AS jsonb))",
                "agent-" + UUID.randomUUID(), "system prompt", "[" + kbId + "]");

        mockMvc.perform(delete("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40903));
    }

    private User createUser(UserRole role) {
        User user = new User("node-" + UUID.randomUUID(), passwordEncoder.encode(PASSWORD), role);
        user.setEnabled(true);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(), "$.data.accessToken");
    }

    private long createKb() throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"kb-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        createdKbIds.add(id);
        return id;
    }

    private NodeData createNode(long kbId, Long parentId, String name) throws Exception {
        String content = parentId == null
                ? "{\"name\":\"" + name + "\"}"
                : "{\"parentId\":" + parentId + ",\"name\":\"" + name + "\"}";
        String body = mockMvc.perform(post("/api/knowledge-bases/" + kbId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();
        String path = JsonPath.read(body, "$.data.path");
        return new NodeData(id, path);
    }

    private Map<Long, String> listNodePaths(long kbId) throws Exception {
        String body = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> nodes = JsonPath.read(body, "$.data");
        Map<Long, String> result = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            result.put(((Number) node.get("id")).longValue(), (String) node.get("path"));
        }
        return result;
    }

    private record NodeData(long id, String path) {
    }
}
