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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库与节点树的行为：移动节点时 ltree 路径重写、同级名称唯一（含仅作用于根节点的部分索引）、
 * 非空库禁止删除的守卫、跨知识库挂父节点被拒，以及读写分离的权限矩阵。
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
    void moveUpdatesParentId() throws Exception {
        long kbId = createKb();
        NodeData a = createNode(kbId, null, "A");
        NodeData b = createNode(kbId, a.id(), "B");
        NodeData c = createNode(kbId, b.id(), "C");
        NodeData d = createNode(kbId, null, "D");

        mockMvc.perform(patch("/api/nodes/" + b.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + d.id() + "}"))
                .andExpect(status().isOk());

        Map<Long, Map<String, Object>> nodes = listNodes(kbId);
        assertEquals(Long.valueOf(d.id()), parentIdOf(nodes, b.id()), "B's parentId must follow the move");
        assertEquals(Long.valueOf(b.id()), parentIdOf(nodes, c.id()), "C must stay under B");

        mockMvc.perform(patch("/api/nodes/" + b.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null}"))
                .andExpect(status().isOk());

        nodes = listNodes(kbId);
        assertNull(parentIdOf(nodes, b.id()), "B is a root after moving to root");
        assertEquals(Long.valueOf(b.id()), parentIdOf(nodes, c.id()), "C must still point at B");
    }

    @Test
    void moveEmptiesOldParent() throws Exception {
        long kbId = createKb();
        NodeData a = createNode(kbId, null, "A");
        NodeData b = createNode(kbId, a.id(), "B");

        mockMvc.perform(patch("/api/nodes/" + b.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/nodes/" + a.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void moveRejectsDuplicateSiblingName() throws Exception {
        long kbId = createKb();
        NodeData a = createNode(kbId, null, "A");
        createNode(kbId, a.id(), "X");
        NodeData b = createNode(kbId, null, "B");
        NodeData bChild = createNode(kbId, b.id(), "X");

        mockMvc.perform(patch("/api/nodes/" + bChild.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + a.id() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void renameReflectsNewNameInResponse() throws Exception {
        long kbId = createKb();
        NodeData node = createNode(kbId, null, "old-name");

        mockMvc.perform(patch("/api/nodes/" + node.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"new-name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("new-name"))
                .andExpect(jsonPath("$.data.path").value("n" + node.id()));
    }

    @Test
    void moveReflectsNewPathInResponse() throws Exception {
        long kbId = createKb();
        NodeData a = createNode(kbId, null, "A");
        NodeData b = createNode(kbId, null, "B");

        mockMvc.perform(patch("/api/nodes/" + b.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + a.id() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value((int) a.id()))
                .andExpect(jsonPath("$.data.path").value("n" + a.id() + ".n" + b.id()));
    }

    @Test
    void patchWithNonNumericParentIdRejected() throws Exception {
        long kbId = createKb();
        NodeData node = createNode(kbId, null, "A");

        mockMvc.perform(patch("/api/nodes/" + node.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").doesNotExist());
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
        Map<Long, String> result = new HashMap<>();
        listNodes(kbId).forEach((id, node) -> result.put(id, (String) node.get("path")));
        return result;
    }

    private Map<Long, Map<String, Object>> listNodes(long kbId) throws Exception {
        String body = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> nodes = JsonPath.read(body, "$.data");
        Map<Long, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            result.put(((Number) node.get("id")).longValue(), node);
        }
        return result;
    }

    private static Long parentIdOf(Map<Long, Map<String, Object>> nodes, long nodeId) {
        Object parentId = nodes.get(nodeId).get("parentId");
        return parentId == null ? null : ((Number) parentId).longValue();
    }

    private record NodeData(long id, String path) {
    }
}
