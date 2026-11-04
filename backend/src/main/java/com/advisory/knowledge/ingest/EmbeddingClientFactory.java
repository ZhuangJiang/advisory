package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.provider.repo.ModelLookup;
import com.advisory.provider.repo.ModelLookup.ModelRow;
import com.advisory.provider.service.CryptoService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

/**
 * Builds one Spring AI {@link EmbeddingModel} for a {@code models}/{@code model_providers} row pair.
 *
 * <p>Clients are constructed programmatically instead of via auto-configuration on purpose: a
 * {@code models} row maps to exactly one provider at runtime, while the Spring AI starters wire a
 * single client from static {@code application.yml} values. Vector storage is handled elsewhere
 * (Task 8 issues vector search as explicit SQL), so no Spring AI vector-store bean exists.</p>
 */
public class EmbeddingClientFactory {

    /** Hard timeouts required by the spec for a single provider call. */
    static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);

    private final ModelLookup lookup;
    private final int configuredDimension;
    private final CryptoService cryptoService;

    public EmbeddingClientFactory(JdbcTemplate jdbc, int configuredDimension,
                                  CryptoService cryptoService) {
        this.lookup = new ModelLookup(jdbc);
        this.configuredDimension = configuredDimension;
        this.cryptoService = cryptoService;
    }

    /**
     * Resolves the model row and builds the matching client. A missing model, a disabled
     * model/provider, a wrong {@code capability} or an unknown provider type is a validation error
     * (never an NPE) — the ingest worker turns it into a document failure with the message intact.
     *
     * <p>The returned record pairs the client with the request options it needs (the model name in
     * the concrete provider options type):{@code EmbeddingRequest(batch, client.options())} always
     * carries the configured model, independent of provider-guessable defaults.</p>
     */
    public EmbeddingClient forEmbedding(long embeddingModelId) {
        ModelRow row = lookup.find(embeddingModelId).orElseThrow(() -> new AppException(
                ErrorCode.VALIDATION_FAILED,
                "未找到 embedding 模型 (id=" + embeddingModelId + ")"));
        if (!row.capability().equals("EMBEDDING")) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "模型不是 embedding 模型 (capability=" + row.capability() + ")");
        }
        if (!row.enabled() || !row.providerEnabled()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "embedding 模型或其提供商不可用");
        }
        if (row.modelName() == null || row.modelName().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "embedding 模型缺少 model_name");
        }
        // Cheap early failure before any provider network call: a models row declaring a
        // different dimension than app.embedding.dimension can only produce unusable vectors.
        if (row.dimension() != null && row.dimension() != configuredDimension) {
            throw new AppException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                    "模型向量维度不一致: models.dimension=" + row.dimension()
                            + ", app.embedding.dimension=" + configuredDimension
                            + " (model id=" + embeddingModelId + ")");
        }
        String apiKey = resolveApiKey(row.providerId(), row.apiKeyCipher());
        return switch (row.providerType()) {
            case "OPENAI_COMPATIBLE" -> buildOpenAiClient(row.baseUrl(), row.modelName(), apiKey);
            case "OLLAMA" -> buildOllamaClient(row.baseUrl(), row.modelName());
            default -> throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "未知的提供商类型: " + row.providerType());
        };
    }

    public record EmbeddingClient(EmbeddingModel model, EmbeddingOptions options) {
    }

    /**
     * Decrypts the provider's stored {@code api_key_cipher} with the Task 9 {@link CryptoService}
     * (AES-GCM; this body replaced ruling R5's plaintext passthrough seam). A key that cannot be
     * decrypted fails fast with a clear {@link AppException} — the ingest worker then fails the
     * document with that readable message; the ciphertext is never sent to a provider as if it
     * were the bearer token. A missing key stays {@code null} (OpenAI clients without
     * authentication substitute their own placeholder below).
     */
    String resolveApiKey(long providerId, String apiKeyCipher) {
        if (apiKeyCipher == null || apiKeyCipher.isBlank()) {
            return null;
        }
        try {
            return cryptoService.decrypt(apiKeyCipher);
        } catch (RuntimeException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "提供商 API 密钥无法解密 (provider id=" + providerId + "): " + reason);
        }
    }

    private static EmbeddingClient buildOpenAiClient(String baseUrl, String modelName, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "提供商缺少 base_url");
        }
        String trimmedUrl = baseUrl.trim().replaceAll("/+$", "");
        // Some OpenAI-compatible deployments run without authentication; the client still requires
        // a non-null key, so an empty placeholder is sent.
        String effectiveKey = (apiKey == null || apiKey.isBlank()) ? "unused" : apiKey;
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder().model(modelName).build();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(trimmedUrl)
                .apiKey(effectiveKey)
                .restClientBuilder(restClientWithTimeouts())
                .build();
        return new EmbeddingClient(
                new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options), options);
    }

    private static EmbeddingClient buildOllamaClient(String baseUrl, String modelName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "提供商缺少 base_url");
        }
        String trimmedUrl = baseUrl.trim().replaceAll("/+$", "");
        OllamaOptions options = OllamaOptions.builder().model(modelName).build();
        OllamaApi api = OllamaApi.builder()
                .baseUrl(trimmedUrl)
                .restClientBuilder(restClientWithTimeouts())
                .build();
        return new EmbeddingClient(
                new OllamaEmbeddingModel(api, options, ObservationRegistry.NOOP,
                        ModelManagementOptions.defaults()),
                options);
    }

    private static RestClient.Builder restClientWithTimeouts() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(PROVIDER_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(PROVIDER_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
