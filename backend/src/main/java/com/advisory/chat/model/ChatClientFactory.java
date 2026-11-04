package com.advisory.chat.model;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.provider.repo.ModelLookup;
import com.advisory.provider.repo.ModelLookup.ModelRow;
import com.advisory.provider.service.CryptoService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds one Spring AI {@link ChatModel} for a {@code models} row (that row's provider included).
 *
 * <p>Same rationale as {@code EmbeddingClientFactory}: clients are constructed programmatically
 * because the provider/model is chosen per agent at runtime, which starter auto-configuration (one
 * client from static {@code application.yml}) cannot express. Both factories resolve their row
 * through {@link ModelLookup}, so the SQL exists once.</p>
 *
 * <p>The agent's {@code temperature} is applied as the request default. It is passed in rather than
 * read from the row because it belongs to the agent snapshot, not to the model definition.</p>
 */
public class ChatClientFactory {

    /** Matches the orchestrator's 60s per-step budget: the HTTP call fails before the step does. */
    static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);

    private final ModelLookup lookup;
    private final CryptoService cryptoService;

    public ChatClientFactory(JdbcTemplate jdbc, CryptoService cryptoService) {
        this.lookup = new ModelLookup(jdbc);
        this.cryptoService = cryptoService;
    }

    /**
     * @param chatModelId  the {@code models.id} the agent points at
     * @param temperature  the agent's temperature; {@code null} leaves the provider default
     * @throws AppException when the row is missing, is not a {@code CHAT} model, is disabled, or
     *                      names a provider type this build cannot talk to
     */
    public ChatClient forChat(long chatModelId, BigDecimal temperature) {
        ModelRow row = lookup.find(chatModelId).orElseThrow(() -> new AppException(
                ErrorCode.VALIDATION_FAILED, "未找到 chat 模型 (id=" + chatModelId + ")"));
        if (!"CHAT".equals(row.capability())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "模型不是 chat 模型 (capability=" + row.capability() + ")");
        }
        if (!row.enabled() || !row.providerEnabled()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chat 模型或其提供商不可用");
        }
        if (row.modelName() == null || row.modelName().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chat 模型缺少 model_name");
        }
        if (row.baseUrl() == null || row.baseUrl().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "提供商缺少 base_url");
        }
        Double temperatureValue = temperature == null ? null : temperature.doubleValue();
        String apiKey = resolveApiKey(row.providerId(), row.apiKeyCipher());
        return switch (row.providerType()) {
            case "OPENAI_COMPATIBLE" -> buildOpenAiClient(row.baseUrl(), row.modelName(),
                    apiKey, temperatureValue);
            case "OLLAMA" -> buildOllamaClient(row.baseUrl(), row.modelName(), temperatureValue);
            default -> throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "未知的提供商类型: " + row.providerType());
        };
    }

    /** The client plus the options that carry the model name and temperature on every call. */
    public record ChatClient(ChatModel model, ChatOptions options) {
    }

    /**
     * Decrypts the stored {@code api_key_cipher} (AES-GCM). A key that cannot be decrypted fails
     * fast with a readable message instead of being sent to the provider as a bearer token; a
     * missing key stays {@code null} and OpenAI clients substitute their own placeholder, because
     * some OpenAI-compatible deployments run unauthenticated.
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

    private static ChatClient buildOpenAiClient(String baseUrl, String modelName, String apiKey,
                                                Double temperature) {
        String trimmedUrl = baseUrl.trim().replaceAll("/+$", "");
        String effectiveKey = (apiKey == null || apiKey.isBlank()) ? "unused" : apiKey;
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(modelName);
        if (temperature != null) {
            options.temperature(temperature);
        }
        OpenAiChatOptions built = options.build();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(trimmedUrl)
                .apiKey(effectiveKey)
                .restClientBuilder(restClientWithTimeouts())
                .build();
        return new ChatClient(
                OpenAiChatModel.builder().openAiApi(api).defaultOptions(built).build(), built);
    }

    private static ChatClient buildOllamaClient(String baseUrl, String modelName, Double temperature) {
        String trimmedUrl = baseUrl.trim().replaceAll("/+$", "");
        OllamaOptions.Builder options = OllamaOptions.builder().model(modelName);
        if (temperature != null) {
            options.temperature(temperature);
        }
        OllamaOptions built = options.build();
        OllamaApi api = OllamaApi.builder()
                .baseUrl(trimmedUrl)
                .restClientBuilder(restClientWithTimeouts())
                .build();
        return new ChatClient(
                OllamaChatModel.builder()
                        .ollamaApi(api)
                        .defaultOptions(built)
                        .modelManagementOptions(ModelManagementOptions.defaults())
                        .build(),
                built);
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
