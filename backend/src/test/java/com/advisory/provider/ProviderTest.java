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
 * Task 9: model providers, encrypted API keys, connectivity probes and model CRUD.
 *
 * <p>Probe tests run against the JDK's built-in {@link HttpServer} on an ephemeral port (no extra
 * test dependency): each failure class gets its own handler configuration so no test depends on
 * timing except the single timeout case, whose server sleeps on a daemon thread and is shut down in
 * a try-with-resources block. Rows are named with the {@code prov9-} prefix and removed in
 * {@code @AfterEach} (agents and knowledge bases first, providers cascade their models).</p>
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
        // Referencing rows first: agents/kb hold ON DELETE RESTRICT references to models,
        // models cascade off their provider.
        jdbc.update("DELETE FROM agents WHERE name LIKE 'prov9-agent-%'");
        jdbc.update("DELETE FROM knowledge_bases WHERE name LIKE 'prov9-kb-%'");
        jdbc.update("DELETE FROM model_providers WHERE name LIKE 'prov9-%'");
    }

    // ------------------------------------------------------------------ 1. key encryption

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

        // Crypto contract: random IV per encryption, lossless round-trip, malformed input rejected.
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
        raw[raw.length - 1] ^= 0x1; // corrupt the GCM tag: must fail loudly, never return garbage
        assertThrows(IllegalStateException.class,
                () -> cryptoService.decrypt(Base64.getEncoder().encodeToString(raw)));
    }

    // ------------------------------------------------------------------ 2. probe failure classes

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

            // base-url normalization: no /v1 suffix -> {base}/v1/models, key sent as bearer
            assertEquals("/v1/models", server.lastPath());
            assertEquals("Bearer probe-key-12345", server.lastAuthorization());

            // remote=true returns the live remote names through the models endpoint
            String remoteBody = mockMvc.perform(get("/api/providers/" + id + "/models")
                            .param("remote", "true")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(true))
                    .andExpect(jsonPath("$.data.models[0]").value("gpt-4o-mini"))
                    .andReturn().getResponse().getContentAsString();
            assertNull(JsonPath.read(remoteBody, "$.data.errorCode"));

            // local rows (remote=false / absent) are the empty local table for this provider
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
        } // port released: nothing listens on it anymore
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
        // Handler sleeps well past the 10s probe timeout on a daemon thread; the client aborts
        // at ~10s and the try-with-resources interrupts the sleeping handler.
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

    // ------------------------------------------------------------------ 3. dimension validation

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

    // ------------------------------------------------------------------ 4. references block deletes

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

    // ---------------------------------------------------- 4b. provider delete + model patch

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

        // the 1024 rule follows the stored capability on updates too
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
        // an existing embedding row without a dimension change is still valid
        mockMvc.perform(patch("/api/models/" + embedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    // ------------------------------------------------------------------ 5. permission matrix

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

        // reading the provider list is open to every authenticated user (Task 16 renders the
        // Agent form from it)
        mockMvc.perform(get("/api/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * {@code remote=true} spends the configured API key against an external host, so it is
     * ADMIN-only; the local model list stays open to every authenticated user.
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

            // remote=false / absent: still just authenticated
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
     * Global list contract: {@code page} is 0-based, {@code size} defaults to 20 and is capped at
     * 100, rows come back ordered by id and the envelope carries {@code PageResponse} fields.
     */
    @Test
    void providerListIsPaginated() throws Exception {
        // size above the cap is clamped to 100, never echoed back
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

        // the created rows are all present somewhere in the ordered listing (id asc)
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
     * Base-URL normalization seen from the wire: trailing slash stripped, a base that already ends
     * in {@code /v1} does not get a second {@code /v1}, and OLLAMA gets {@code /api/tags} with
     * <em>no</em> Authorization header even though a key is configured.
     */
    @Test
    void probeNormalizesBaseUrlsAndNeverSendsKeysToOllama() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start(
                "{\"data\":[{\"id\":\"gpt-4o-mini\"}],\"models\":[{\"name\":\"llama3:latest\"}]}")) {

            // trailing slash: http://host:port/  ->  /v1/models (an unstripped slash would give
            // //v1/models)
            long trailing = createProvider("OPENAI_COMPATIBLE", server.baseUrl() + "/", "trailing-key-111");
            probeExpectingAuthorization(server, trailing, "Bearer trailing-key-111");
            assertEquals("/v1/models", server.lastPath());

            // base already ending in /v1: the /v1 suffix must not be appended twice
            // (http://host:port/v1 + /v1/models would be the path /v1/v1/models)
            long v1Base = createProvider("OPENAI_COMPATIBLE", server.baseUrl() + "/v1", "v1-key-22222");
            probeExpectingAuthorization(server, v1Base, "Bearer v1-key-22222");
            assertEquals("/v1/models", server.lastPath());

            // OLLAMA probes /api/tags and never carries the configured key
            long ollama = createProvider("OLLAMA", server.baseUrl() + "/", "ollama-key-333");
            probeExpectingAuthorization(server, ollama, null);
            assertEquals("/api/tags", server.lastPath());
            assertNull(server.lastAuthorization(),
                    "an OLLAMA probe must not send the Authorization header");
        }
    }

    // ------------------------------------------------------------------ 6. import idempotency

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

            // second import of the same payload must not duplicate anything
            mockMvc.perform(post("/api/providers/" + id + "/models/import")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(importBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(3));
            assertEquals(3, localModelCount(id), "re-import must leave exactly three rows");

            // a wrong embedding dimension fails the whole import: nothing is inserted
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

    // ------------------------------------------------------------------ 7. patch clears / re-encrypts

    @Test
    void patchClearsAndReencryptsKey() throws Exception {
        try (FakeProviderServer server = FakeProviderServer.start("{\"data\":[{\"id\":\"gpt-4o\"}]}")) {
            long id = createProvider("OPENAI_COMPATIBLE", server.baseUrl(), "first-key-AAAA-777");

            probeExpectingAuthorization(server, id, "Bearer first-key-AAAA-777");

            // clearing: apiKey "" wipes the stored key and the masked value disappears
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

            // re-encryption: a new key is stored as a fresh ciphertext and used as the bearer token
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
            // the PATCH response carries the mask only: no plaintext, no ciphertext, no cipher field
            assertFalse(patchBody.contains(secondKey), "the PATCH response must not echo the key");
            assertFalse(patchBody.contains(cipher), "the PATCH response must not echo the ciphertext");
            assertFalse(patchBody.contains("apiKeyCipher"), "no response field may expose the cipher");
            Map<String, Object> rekeyed = providerJson(getProviders(adminToken), id);
            assertNotNull(rekeyed);
            assertEquals(secondKey.substring(0, 4) + "****", rekeyed.get("apiKeyMasked"));
            probeExpectingAuthorization(server, id, "Bearer " + secondKey);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Probes the provider and asserts the Authorization header the fake server actually received. */
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
        // size=100 (the cap) so a lookup by id always lands on page 0 regardless of how many
        // providers other tests left behind; the pagination contract itself is asserted by
        // providerListIsPaginated.
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
     * Minimal fake provider on an ephemeral port: records the paths and Authorization headers it
     * receives, can answer with a configurable status/body and can hang past the probe timeout.
     * Handler threads are daemons and are interrupted on close so a sleeping handler can never
     * keep the JVM (or the suite) alive.
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
                // probe aborted (timeout) or the test closed the server: nothing to report
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
