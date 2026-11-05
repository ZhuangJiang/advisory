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
 * 为 {@code models} 与 {@code model_providers} 的一对行构造 Spring AI {@link EmbeddingModel}。
 *
 * <p>刻意不用自动配置而是手工构造：一个 {@code models} 行在运行时对应唯一提供商，而 Spring AI starter 只会
 * 依据静态 {@code application.yml} 装配一个客户端。向量存储也不走 Spring AI（Task 8 直接用 SQL 做向量检索），
 * 因此整个应用里没有 vector-store bean。</p>
 */
public class EmbeddingClientFactory {

    /** 规格要求的单次提供商调用硬超时。 */
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
     * 解析模型行并构造对应客户端。模型不存在、模型或提供商已停用、能力不匹配、提供商类型不支持，
     * 一律作为校验错误抛出（绝不让 NPE 冒出去），入库线程据此把文档置为失败并保留可读原因。
     *
     * <p>返回值把客户端与它需要的请求选项绑在一起（模型名放在具体提供商的选项类型里）：
     * {@code EmbeddingRequest(batch, client.options())} 因此总能带上配置中的模型名，
     * 不依赖提供商自行猜测的默认值。</p>
     *
     * @param embeddingModelId 要构造客户端的模型行主键，对应 {@code models.id}；
     *                         该行的提供商配置决定了协议、endpoint 与密钥
     * @return 客户端及其选项
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
        // 在发起任何网络调用之前就快速失败：模型行声明的维度与 app.embedding.dimension 不一致时，
        // 产出的向量必然无法入库。
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

    /** 客户端与其请求选项的组合。 */
    public record EmbeddingClient(EmbeddingModel model, EmbeddingOptions options) {
    }

    /**
     * 用 {@link CryptoService}（AES-GCM）解密提供商保存的 {@code api_key_cipher}。
     *
     * <p>解不开时立即抛 {@link AppException} 并给出明确原因，入库线程会把该文档置为失败并保留这句话；
     * 绝不能把密文当作 bearer token 发给提供商——那样只会换回一个毫无线索的 401。未配置密钥时返回
     * {@code null}，由下面的 OpenAI 客户端补一个占位符。</p>
     *
     * @param provider 提供商
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

    private static EmbeddingClient buildOpenAiClient(String baseUrl, String modelName, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "提供商缺少 base_url");
        }
        String trimmedUrl = baseUrl.trim().replaceAll("/+$", "");
        // 有些 OpenAI 兼容实现以无鉴权方式运行，而客户端不允许密钥为 null，
        // 因此这里送一个空占位符。
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
