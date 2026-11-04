package com.advisory.provider;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import com.advisory.provider.service.CryptoService;
import com.advisory.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 9：模型提供商、API Key 加密、连通性探测与模型增删改查。
 *
 * <p>探测类用例都打在 JDK 自带的 {@link HttpServer} 上，端口临时分配，不引入额外的测试依赖：
 * 每种失败分类各配一套 handler 行为，因此除了唯一那个超时用例之外，没有任何用例依赖时序。
 * 超时用例的服务端在守护线程上长睡，由 try-with-resources 负责中断与关闭。</p>
 *
 * <p>数据行统一以 {@code prov9-} 为前缀，并在 {@code @AfterEach} 里按依赖顺序清理：
 * 先删 agent 与知识库，提供商删除时其模型行随级联一起消失。</p>
 */
class ProviderTest extends IntegrationTestBase {

    private static final String PASSWORD = "provider-pass-1";
    private static final String PLAINTEXT_KEY = "sk-live-plaintext-key-123456";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CryptoService cryptoService;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void createTokens() throws Exception {
        adminToken = login(createUser(UserRole.ADMIN));
        userToken = login(createUser(UserRole.USER));
    }

    @AfterEach
    void cleanup() {
        // 先清引用方：agent 与知识库对 models 是 ON DELETE RESTRICT，
        // 而 models 自身随提供商级联删除。
        jdbc.update("DELETE FROM agents WHERE name LIKE 'prov9-agent-%'");
        jdbc.update("DELETE FROM knowledge_bases WHERE name LIKE 'prov9-kb-%'");
        jdbc.update("DELETE FROM model_providers WHERE name LIKE 'prov9-%'");
    }

    // ------------------------------------------------------------------ 1. 密钥加密

    @Test
    void apiKeyIsEncryptedAndMasked() throws Exception {
        long id = createProvider("OPENAI_COMPATIBLE", "http://127.0.0.1:9", PLAINTEXT_KEY);

        String cipher = jdbc.queryForObject(
                "SELECT api_key_cipher FROM model_providers WHERE id = ?", String.class, id);
        assertNotNull(cipher, "api_key_cipher must be stored");
        assertNotEquals(PLAINTEXT_KEY, cipher, "the stored column must not hold the plaintext");
        assertFalse(cipher.isBlank(), "the stored cipher must not be empty");

        String listBody = getProviders(adminToken);
        Map<String, Object> provider = providerJson(listBody, id);
        assertNotNull(provider, "the created provider must be listed");
        String masked = (String) provider.get("apiKeyMasked");
        assertNotNull(masked, "a provider with a key must expose apiKeyMasked");
        assertTrue(masked.startsWith(PLAINTEXT_KEY.substring(0, 4) + "****"),
                "masked key must be first 4 chars + ****, got: " + masked);
        assertFalse(listBody.contains(PLAINTEXT_KEY), "response must never contain the plaintext key");
        assertFalse(listBody.contains(cipher), "response must never contain the ciphertext");
        assertFalse(provider.containsKey("apiKeyCipher"), "no response field may expose the cipher");

        // 加密约定：每次加密用随机 IV、往返无损、畸形输入一律拒绝。
        String first = cryptoService.encrypt(PLAINTEXT_KEY);
        String second = cryptoService.encrypt(PLAINTEXT_KEY);
        assertNotEquals(first, second, "two encryptions of the same plaintext must differ (random IV)");
        assertEquals(PLAINTEXT_KEY, cryptoService.decrypt(first));
        assertNull(cryptoService.encrypt(null));
        assertNull(cryptoService.encrypt("   "), "blank input must pass through as null");
        assertNull(cryptoService.decrypt(null));
        assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt("!!! not base64 !!!"));
        assertThrows(IllegalArgumentException.class,
                () -> cryptoService.decrypt(Base64.getEncoder().encodeToString(new byte[4])));
        byte[] raw = Base64.getDecoder().decode(first);
        raw[raw.length - 1] ^= 0x1; // 破坏 GCM 认证标签：必须显式报错，绝不能返回垃圾数据
        assertThrows(IllegalStateException.class,
                () -> cryptoService.decrypt(Base64.getEncoder().encodeToString(raw)));
    }

    // ------------------------------------------------------------------ 2. 探测的失败分类

    @Test
    void probeSucceedsAgainstHealthyServer() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start(
                "{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"text-embedding-3-small\"}]}")) {
            long id = createProvider("OPENAI_COMPATIBLE", server.baseUrl(), "probe-key-12345");

            String body = mockMvc.perform(post("/api/providers/" + id + "/test")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"modelName\":\"gpt-4o-mini\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(true))
                    .andReturn().getResponse().getContentAsString();
            assertNull(JsonPath.read(body, "$.data.errorCode"), "a successful probe has no error code");
            assertEquals(List.of("gpt-4o-mini", "text-embedding-3-small"),
                    JsonPath.read(body, "$.data.remoteModels"));

            // base-url 归一化：不带 /v1 后缀时补成 {base}/v1/models，密钥以 bearer 形式发送
            assertEquals("/v1/models", server.lastPath());
            assertEquals("Bearer probe-key-12345", server.lastAuthorization());

            // remote=true 时经 models 接口返回远端实时模型名
            String remoteBody = mockMvc.perform(get("/api/providers/" + id + "/models")
                            .param("remote", "true")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(true))
                    .andExpect(jsonPath("$.data.models[0]").value("gpt-4o-mini"))
                    .andReturn().getResponse().getContentAsString();
            assertNull(JsonPath.read(remoteBody, "$.data.errorCode"));

            // remote=false 或不传时只回本地行，而该提供商本地表为空
            mockMvc.perform(get("/api/providers/" + id + "/models")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Test
    void probeMapsUnauthorizedToAuthFailed() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start("{\"error\":\"invalid key\"}", 401, 0)) {
            long id = createProvider("OPENAI_COMPATIBLE", server.baseUrl(), "wrong-key");

            mockMvc.perform(post("/api/providers/" + id + "/test")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(false))
                    .andExpect(jsonPath("$.data.errorCode").value("AUTH_FAILED"));
        }
    }

    @Test
    void probeMapsConnectionRefusedToNetworkUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        } // 端口已释放：此时无人监听
        long id = createProvider("OPENAI_COMPATIBLE", "http://127.0.0.1:" + closedPort, null);

        mockMvc.perform(post("/api/providers/" + id + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.errorCode").value("NETWORK_UNREACHABLE"));
    }

    @Test
    void probeTimesOutAgainstHangingServer() throws Exception {
        // handler 在守护线程上睡过 10 秒的探测超时；客户端在 10 秒左右主动放弃，
        // 随后 try-with-resources 中断仍在睡眠的 handler。
        try (FakeProviderServer server = FakeProviderServer.start("{\"data\":[]}", 200, 30_000)) {
            long id = createProvider("OPENAI_COMPATIBLE", server.baseUrl(), null);

            long startedAt = System.nanoTime();
            mockMvc.perform(post("/api/providers/" + id + "/test")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(false))
                    .andExpect(jsonPath("$.data.errorCode").value("TIMEOUT"));
            long elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
            assertTrue(elapsedSeconds < 15, "the probe must abort at its 10s timeout, took "
                    + elapsedSeconds + "s");
        }
    }

    @Test
    void probeReportsModelNotFoundWhenNameAbsent() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start(
                "{\"data\":[{\"id\":\"gpt-4o-mini\"}]}")) {
            long id = createProvider("OPENAI_COMPATIBLE", server.baseUrl(), "probe-key");

            mockMvc.perform(post("/api/providers/" + id + "/test")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"modelName\":\"ghost-model-xyz\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(false))
                    .andExpect(jsonPath("$.data.errorCode").value("MODEL_NOT_FOUND"))
                    .andExpect(jsonPath("$.data.remoteModels[0]").value("gpt-4o-mini"));
        }
    }

    // ------------------------------------------------------------------ 3. 维度校验

    @Test
    void rejectsEmbeddingDimensionOtherThan1024() throws Exception {
        long id = createProvider("OLLAMA", "http://127.0.0.1:9", null);

        mockMvc.perform(post("/api/providers/" + id + "/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"prov9-wrong-dim\",\"capability\":\"EMBEDDING\",\"dimension\":768}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40010));

        mockMvc.perform(post("/api/providers/" + id + "/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"prov9-right-dim\",\"capability\":\"EMBEDDING\",\"dimension\":1024}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dimension").value(1024));

        mockMvc.perform(post("/api/providers/" + id + "/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"prov9-chat-with-dim\",\"capability\":\"CHAT\",\"dimension\":1024}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void duplicateModelNameConflicts() throws Exception {
        long id = createProvider("OLLAMA", "http://127.0.0.1:9", null);
        String body = "{\"modelName\":\"prov9-dup-model\",\"capability\":\"CHAT\"}";

        mockMvc.perform(post("/api/providers/" + id + "/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/providers/" + id + "/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    // ------------------------------------------------------------------ 4. 被引用时禁止删除

    @Test
    void modelInUseCannotBeDeleted() throws Exception {
        long providerId = createProvider("OLLAMA", "http://127.0.0.1:9", null);
        long chatModelId = createModel(providerId, "prov9-chat-" + UUID.randomUUID(), "CHAT", null);
        long embedModelId = createModel(providerId, "prov9-embed-" + UUID.randomUUID(), "EMBEDDING", 1024);
        long unusedModelId = createModel(providerId, "prov9-unused-" + UUID.randomUUID(), "CHAT", null);

        jdbc.update("INSERT INTO agents (name, system_prompt, chat_model_id) VALUES (?, ?, ?)",
                "prov9-agent-" + UUID.randomUUID(), "test prompt", chatModelId);
        jdbc.update("INSERT INTO knowledge_bases (name, embedding_model_id) VALUES (?, ?)",
                "prov9-kb-" + UUID.randomUUID(), embedModelId);

        mockMvc.perform(delete("/api/models/" + chatModelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40904));
        mockMvc.perform(delete("/api/models/" + embedModelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40904));

        assertNotNull(modelName(chatModelId), "the referenced chat model must survive");
        assertNotNull(modelName(embedModelId), "the referenced embedding model must survive");

        mockMvc.perform(delete("/api/models/" + unusedModelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertNull(modelName(unusedModelId), "an unreferenced model must be deleted");
    }

    // ---------------------------------------------------- 4b. 删除提供商 + 修改模型

    @Test
    void providerDeleteChecksReferencesAndCascadesModels() throws Exception {
        long providerId = createProvider("OLLAMA", "http://127.0.0.1:9", null);
        long modelId = createModel(providerId, "prov9-ref-" + UUID.randomUUID(), "CHAT", null);
        String agentName = "prov9-agent-" + UUID.randomUUID();
        jdbc.update("INSERT INTO agents (name, system_prompt, chat_model_id) VALUES (?, ?, ?)",
                agentName, "test prompt", modelId);

        Map<String, Object> listed = providerJson(getProviders(adminToken), providerId);
        assertNotNull(listed);
        assertEquals(1, ((Number) listed.get("modelCount")).longValue(),
                "the provider must report its model count");

        mockMvc.perform(delete("/api/providers/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40904));
        assertNotNull(modelName(modelId), "a provider with a referenced model must survive");

        jdbc.update("DELETE FROM agents WHERE name = ?", agentName);
        mockMvc.perform(delete("/api/providers/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertNull(modelName(modelId), "deleting the provider must cascade its models");
    }

    @Test
    void patchModelUpdatesFieldsAndRechecksDimensionRules() throws Exception {
        long providerId = createProvider("OLLAMA", "http://127.0.0.1:9", null);
        long chatId = createModel(providerId, "prov9-patch-chat-" + UUID.randomUUID(), "CHAT", null);
        long embedId = createModel(providerId, "prov9-patch-embed-" + UUID.randomUUID(),
                "EMBEDDING", 1024);

        String renamed = "prov9-renamed-" + UUID.randomUUID();
        mockMvc.perform(patch("/api/models/" + chatId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"" + renamed + "\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value(renamed))
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertEquals(renamed, modelName(chatId));

        // 更新时同样按库里已存的 capability 施加 1024 维规则
        mockMvc.perform(patch("/api/models/" + chatId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimension\":1024}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(patch("/api/models/" + embedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dimension\":512}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40010));
        // 已存在的 embedding 行只要维度不变就仍然合法
        mockMvc.perform(patch("/api/models/" + embedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    // ------------------------------------------------------------------ 5. 权限矩阵

    @Test
    void userCannotWriteProviders() throws Exception {
        long id = createProvider("OLLAMA", "http://127.0.0.1:9", null);

        mockMvc.perform(post("/api/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"prov9-nope\",\"type\":\"OLLAMA\",\"baseUrl\":\"http://127.0.0.1:9\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(post("/api/providers/" + id + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // 提供商列表对所有已登录用户开放（Task 16 的 Agent 表单要读它来渲染）
        mockMvc.perform(get("/api/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * {@code remote=true} 会拿配置好的 API Key 去打外部主机，因此仅限 ADMIN；
     * 本地模型列表则对所有已登录用户开放。
     */
    @Test
    void remoteModelListingRequiresAdminRole() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start(
                "{\"data\":[{\"id\":\"gpt-4o-mini\"}]}")) {
            long id = createProvider("OPENAI_COMPATIBLE", server.baseUrl(), "remote-key-1");

            mockMvc.perform(get("/api/providers/" + id + "/models")
                            .param("remote", "true")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));

            // remote=false 或不传：仍只要求已登录
            mockMvc.perform(get("/api/providers/" + id + "/models")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());

            mockMvc.perform(get("/api/providers/" + id + "/models")
                            .param("remote", "true")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(true))
                    .andExpect(jsonPath("$.data.models[0]").value("gpt-4o-mini"));
            assertEquals("/v1/models", server.lastPath(), "the ADMIN probe must really have run");
        }
    }

    /**
     * 全局列表的分页约定：{@code page} 从 0 开始，{@code size} 默认 20、上限 100，
     * 结果按 id 升序返回，外层信封携带 {@code PageResponse} 的各字段。
     */
    @Test
    void providerListIsPaginated() throws Exception {
        // 超过上限的 size 被钳到 100，且不会原样回显
        String oversized = mockMvc.perform(get("/api/providers")
                        .param("size", "1000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100))
                .andReturn().getResponse().getContentAsString();
        long totalBefore = ((Number) JsonPath.read(oversized, "$.data.total")).longValue();
        assertTrue(totalBefore >= 0);

        long first = createProvider("OLLAMA", "http://127.0.0.1:9", null);
        long second = createProvider("OLLAMA", "http://127.0.0.1:9", null);
        long third = createProvider("OLLAMA", "http://127.0.0.1:9", null);

        String page0 = mockMvc.perform(get("/api/providers")
                        .param("page", "0").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String page1 = mockMvc.perform(get("/api/providers")
                        .param("page", "1").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        long total0 = ((Number) JsonPath.read(page0, "$.data.total")).longValue();
        long total1 = ((Number) JsonPath.read(page1, "$.data.total")).longValue();
        assertEquals(totalBefore + 3, total0, "three created providers must be counted");
        assertEquals(total0, total1, "the total is page-independent");
        assertTrue(total0 >= 3);

        long id0 = ((Number) JsonPath.read(page0, "$.data.content[0].id")).longValue();
        long id1 = ((Number) JsonPath.read(page1, "$.data.content[0].id")).longValue();
        assertNotEquals(id0, id1, "page 0 and page 1 must be disjoint rows");
        assertTrue(id0 < id1, "rows are ordered by id ascending");

        // 新建的行在按 id 升序的列表里都能找到，位置不限
        String all = getProviders(adminToken);
        List<Object> ids = JsonPath.read(all, "$.data.content[*].id");
        List<Number> numericIds = new ArrayList<>();
        ids.forEach(value -> numericIds.add((Number) value));
        for (long wanted : new long[] {first, second, third}) {
            assertTrue(numericIds.stream().anyMatch(n -> n.longValue() == wanted),
                    "provider " + wanted + " must appear in the listing");
        }
    }

    /**
     * 从实际请求上验证 base-url 归一化：末尾斜杠被去掉；base 本身已以 {@code /v1} 结尾时不再叠加
     * 一个 {@code /v1}；OLLAMA 走 {@code /api/tags}，即便配了密钥也 <em>不</em> 带 Authorization 头。
     */
    @Test
    void probeNormalizesBaseUrlsAndNeverSendsKeysToOllama() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start(
                "{\"data\":[{\"id\":\"gpt-4o-mini\"}],\"models\":[{\"name\":\"llama3:latest\"}]}")) {

            // 末尾斜杠：http://host:port/ -> /v1/models（不剥掉斜杠就会拼成 //v1/models）
            long trailing = createProvider("OPENAI_COMPATIBLE", server.baseUrl() + "/", "trailing-key-111");
            probeExpectingAuthorization(server, trailing, "Bearer trailing-key-111");
            assertEquals("/v1/models", server.lastPath());

            // base 已以 /v1 结尾：/v1 后缀绝不能再叠加一次
            // （http://host:port/v1 再加 /v1/models 会得到 /v1/v1/models 这样的路径）
            long v1Base = createProvider("OPENAI_COMPATIBLE", server.baseUrl() + "/v1", "v1-key-22222");
            probeExpectingAuthorization(server, v1Base, "Bearer v1-key-22222");
            assertEquals("/v1/models", server.lastPath());

            // OLLAMA 探测 /api/tags，并且从不携带已配置的密钥
            long ollama = createProvider("OLLAMA", server.baseUrl() + "/", "ollama-key-333");
            probeExpectingAuthorization(server, ollama, null);
            assertEquals("/api/tags", server.lastPath());
            assertNull(server.lastAuthorization(),
                    "an OLLAMA probe must not send the Authorization header");
        }
    }

    // ------------------------------------------------------------------ 6. 导入幂等性

    @Test
    void importIsIdempotent() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start(
                "{\"models\":[{\"name\":\"llama3:latest\"},{\"name\":\"nomic-embed-text:latest\"}"
                        + ",{\"name\":\"mistral:7b\"}]}")) {
            long id = createProvider("OLLAMA", server.baseUrl(), null);
            String importBody = "{\"models\":["
                    + "{\"modelName\":\"llama3:latest\",\"capability\":\"CHAT\"},"
                    + "{\"modelName\":\"nomic-embed-text:latest\",\"capability\":\"EMBEDDING\",\"dimension\":1024},"
                    + "{\"modelName\":\"mistral:7b\",\"capability\":\"CHAT\"}]}";

            mockMvc.perform(post("/api/providers/" + id + "/models/import")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(importBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(3))
                    .andExpect(jsonPath("$.data[0].modelName").value("llama3:latest"));
            assertEquals(3, localModelCount(id), "first import must insert three rows");

            // 同一份载荷导入第二次，不得产生任何重复行
            mockMvc.perform(post("/api/providers/" + id + "/models/import")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(importBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(3));
            assertEquals(3, localModelCount(id), "re-import must leave exactly three rows");

            // embedding 维度不对会让整次导入失败：一行都不插入
            String badEmbedding = "prov9-bad-import-" + UUID.randomUUID();
            String validChat = "prov9-good-import-" + UUID.randomUUID();
            mockMvc.perform(post("/api/providers/" + id + "/models/import")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"models\":["
                                    + "{\"modelName\":\"" + badEmbedding + "\",\"capability\":\"EMBEDDING\",\"dimension\":768},"
                                    + "{\"modelName\":\"" + validChat + "\",\"capability\":\"CHAT\"}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40010));
            assertEquals(3, localModelCount(id), "a rejected import must insert nothing");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM models WHERE provider_id = ? AND model_name IN (?, ?)",
                    Integer.class, id, badEmbedding, validChat));
        }
    }

    // ------------------------------------------------------------------ 7. 修改时清空 / 重新加密

    @Test
    void patchClearsAndReencryptsKey() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start("{\"data\":[{\"id\":\"gpt-4o\"}]}")) {
            long id = createProvider("OPENAI_COMPATIBLE", server.baseUrl(), "first-key-AAAA-777");

            probeExpectingAuthorization(server, id, "Bearer first-key-AAAA-777");

            // 清空：apiKey 传 "" 会抹掉已存密钥，掩码值也随之消失
            mockMvc.perform(patch("/api/providers/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"apiKey\":\"\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
            Map<String, Object> cleared = providerJson(getProviders(adminToken), id);
            assertNotNull(cleared);
            assertFalse(cleared.containsKey("apiKeyMasked"),
                    "a cleared key must not leave an apiKeyMasked field behind");
            assertNull(jdbc.queryForObject(
                    "SELECT api_key_cipher FROM model_providers WHERE id = ?", String.class, id));
            probeExpectingAuthorization(server, id, null);

            // 重新加密：新密钥以全新的密文入库，并作为 bearer token 发出去
            String secondKey = "second-key-BBBB-888";
            String patchBody = mockMvc.perform(patch("/api/providers/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"apiKey\":\"" + secondKey + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.apiKeyMasked")
                            .value(secondKey.substring(0, 4) + "****"))
                    .andReturn().getResponse().getContentAsString();

            String cipher = jdbc.queryForObject(
                    "SELECT api_key_cipher FROM model_providers WHERE id = ?", String.class, id);
            assertNotNull(cipher);
            assertNotEquals(secondKey, cipher, "the new key must be encrypted, not stored raw");
            assertEquals(secondKey, cryptoService.decrypt(cipher), "the cipher must round-trip");
            // PATCH 响应只带掩码：既无明文、也无密文，更没有密文字段
            assertFalse(patchBody.contains(secondKey), "the PATCH response must not echo the key");
            assertFalse(patchBody.contains(cipher), "the PATCH response must not echo the ciphertext");
            assertFalse(patchBody.contains("apiKeyCipher"), "no response field may expose the cipher");
            Map<String, Object> rekeyed = providerJson(getProviders(adminToken), id);
            assertNotNull(rekeyed);
            assertEquals(secondKey.substring(0, 4) + "****", rekeyed.get("apiKeyMasked"));
            probeExpectingAuthorization(server, id, "Bearer " + secondKey);
        }
    }

    // ------------------------------------------------------------------ 辅助方法

    /** 探测该提供商，并断言假服务端实际收到的 Authorization 头。 */
    private void probeExpectingAuthorization(FakeProviderServer server, long providerId,
                                             String expectedAuthorization) throws Exception {
        mockMvc.perform(post("/api/providers/" + providerId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));
        assertEquals(expectedAuthorization, server.lastAuthorization(),
                "the probe must send exactly the decrypted key as bearer token");
    }

    private String getProviders(String token) throws Exception {
        // 取 size=100（上限），这样按 id 查找必定落在第 0 页，不受其它用例残留多少行影响；
        // 分页约定本身由 providerListIsPaginated 单独断言。
        return mockMvc.perform(get("/api/providers")
                        .param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long createProvider(String type, String baseUrl, String apiKey) throws Exception {
        String name = "prov9-" + UUID.randomUUID();
        String body = "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"baseUrl\":\"" + baseUrl + "\""
                + (apiKey == null ? "" : ",\"apiKey\":\"" + apiKey + "\"") + "}";
        String response = mockMvc.perform(post("/api/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private long createModel(long providerId, String modelName, String capability, Integer dimension)
            throws Exception {
        String body = "{\"modelName\":\"" + modelName + "\",\"capability\":\"" + capability + "\""
                + (dimension == null ? "" : ",\"dimension\":" + dimension) + "}";
        String response = mockMvc.perform(post("/api/providers/" + providerId + "/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private String modelName(long modelId) {
        List<String> names = jdbc.query("SELECT model_name FROM models WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), modelId);
        return names.isEmpty() ? null : names.get(0);
    }

    private int localModelCount(long providerId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM models WHERE provider_id = ?", Integer.class, providerId);
        return count == null ? 0 : count;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> providerJson(String listBody, long providerId) {
        List<Map<String, Object>> providers = JsonPath.read(listBody, "$.data.content");
        for (Map<String, Object> provider : providers) {
            if (provider.get("id") instanceof Number number && number.longValue() == providerId) {
                return provider;
            }
        }
        return null;
    }

    private User createUser(UserRole role) {
        User user = new User("prov9-user-" + UUID.randomUUID(), passwordEncoder.encode(PASSWORD), role);
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

    /**
     * 跑在临时端口上的极简假提供商：记录收到的请求路径与 Authorization 头，可按配置返回
     * 指定状态码与响应体，也能故意挂住超过探测超时。
     *
     * <p>handler 线程全部是守护线程，关闭时统一中断——否则一个正在长睡的 handler 会把
     * JVM（乃至整个测试套件）拖住不退出。</p>
     */
    private static final class FakeProviderServer implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> authorizations = new CopyOnWriteArrayList<>();
        private final String responseBody;
        private final int status;
        private final long sleepMillis;
        private final String baseUrl;

        private FakeProviderServer(HttpServer server, ExecutorService executor, String responseBody,
                                   int status, long sleepMillis) {
            this.server = server;
            this.executor = executor;
            this.responseBody = responseBody;
            this.status = status;
            this.sleepMillis = sleepMillis;
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        static FakeProviderServer start(String responseBody) throws IOException {
            return start(responseBody, 200, 0);
        }

        static FakeProviderServer start(String responseBody, int status, long sleepMillis)
                throws IOException {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService exec = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "fake-provider-" + UUID.randomUUID());
                thread.setDaemon(true);
                return thread;
            });
            FakeProviderServer fake = new FakeProviderServer(httpServer, exec, responseBody,
                    status, sleepMillis);
            httpServer.createContext("/", fake::handle);
            httpServer.setExecutor(exec);
            httpServer.start();
            return fake;
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization);
            try {
                if (sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                }
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException clientGone) {
                // 探测超时被中断，或用例已关闭服务端：无需上报
            } finally {
                exchange.close();
            }
        }

        String baseUrl() {
            return baseUrl;
        }

        String lastPath() {
            return paths.isEmpty() ? null : paths.get(paths.size() - 1);
        }

        String lastAuthorization() {
            List<String> seen = new ArrayList<>(authorizations);
            return seen.isEmpty() ? null : seen.get(seen.size() - 1);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
