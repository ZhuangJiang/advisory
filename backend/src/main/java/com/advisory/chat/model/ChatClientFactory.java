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
 * 按 {@code models} 表中的一行（含其提供商）构造一个 Spring AI {@link ChatModel}。
 *
 * <p>与 {@code EmbeddingClientFactory} 同理：提供商与模型是运行时按 Agent 选定的，starter 的自动配置
 * （从静态 {@code application.yml} 构造唯一客户端）表达不了这种关系，只能手工构造。两个工厂都通过
 * {@link ModelLookup} 取行，SQL 因此只有一份。</p>
 *
 * <p>Agent 的 {@code temperature} 被设为请求默认值。它是传进来的而不是从模型行读的——温度属于 Agent 快照，
 * 不属于模型定义；同一个模型被两个 Agent 以不同温度使用时，这一点尤其重要。</p>
 */
public class ChatClientFactory {

    /** 与编排器单步 60 秒的预算对齐：HTTP 调用先失败，才不会让整步超时吞掉错误细节。 */
    static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(60);

    private final ModelLookup lookup;
    private final CryptoService cryptoService;

    public ChatClientFactory(JdbcTemplate jdbc, CryptoService cryptoService) {
        this.lookup = new ModelLookup(jdbc);
        this.cryptoService = cryptoService;
    }

    /**
     * @param chatModelId Agent 指向的 {@code models.id}
     * @param temperature Agent 配置的温度；{@code null} 表示沿用提供商默认值
     * @return 已绑定该模型与温度的客户端
     * @throws AppException 模型行不存在、能力不是 {@code CHAT}、已停用，或提供商类型当前版本不支持时抛出
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

    /** 客户端及其调用选项：每次请求都要带上模型名与温度。 */
    public record ChatClient(ChatModel model, ChatOptions options) {
    }

    /**
     * 解密库中的 {@code api_key_cipher}（AES-GCM）。
     *
     * <p>解不开时立刻报错并给出可读提示，而不是把密文当令牌发出去——那样只会收到一个莫名其妙的 401。
     * 未配置密钥时返回 {@code null}，由 OpenAI 客户端填入自己的占位符，因为有些兼容实现本就以无鉴权方式运行。</p>
     *
     * @param cipher 密文
     * @return 明文密钥，未配置时为 {@code null}
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
