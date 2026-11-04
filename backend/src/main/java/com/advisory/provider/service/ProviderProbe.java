package com.advisory.provider.service;

import com.advisory.provider.domain.ModelProvider;
import com.advisory.provider.domain.ProviderType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLException;

/**
 * 用 JDK 自带的 {@link HttpClient} 对某个 {@link ModelProvider} 做实时连通性探测，不引入额外依赖。
 *
 * <p><b>URL 归一化：</b>先去掉基础地址末尾的斜杠；{@code OLLAMA} 拼 {@code {base}/api/tags}，
 * {@code OPENAI_COMPATIBLE} 拼 {@code {base}/v1/models}，但若基础地址本身已以 {@code /v1} 结尾，
 * 则改为 {@code {base}/models}，避免出现 {@code /v1/v1}。提供商有密钥时，解密后以
 * {@code Authorization: Bearer …} 发送；密钥永不进日志。</p>
 *
 * <p><b>错误映射</b>（连接与请求超时均为 10 秒）：401/403 → {@code AUTH_FAILED}；
 * {@code UnknownHostException} / {@code ConnectException} / {@code SSLException} →
 * {@code NETWORK_UNREACHABLE}；{@code HttpTimeoutException} → {@code TIMEOUT}；其余非 2xx 状态
 * （日志里带真实状态码）或响应体无法解析 → {@code NETWORK_UNREACHABLE}。Ollama 的模型名取自
 * {@code models[].name}，OpenAI 取自 {@code data[].id}。列表探测成功但其中没有指定模型名时 →
 * {@code MODEL_NOT_FOUND}。</p>
 */
@Component
public class ProviderProbe {

    private static final Logger log = LoggerFactory.getLogger(ProviderProbe.class);

    /** 规格要求的探测超时，连接与请求共用同一上限。 */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private static final int CAUSE_CHAIN_LIMIT = 8;

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;

    public ProviderProbe(ObjectMapper objectMapper, CryptoService cryptoService) {
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.http = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 探测指定提供商；{@code modelName} 非空时要求它出现在远端列表中，否则返回
     * {@code MODEL_NOT_FOUND}。
     *
     * <p>提供商侧的问题一律不抛异常，全部折算为 {@code ok=false} 加四种错误码之一，
     * 让上层不必区分「调用失败」与「调用成功但结果不可用」。</p>
     *
     * @param provider  待探测的提供商
     * @param modelName 需要确认存在的模型名，可为空
     * @return 探测结果
     */
    public ProbeResult test(ModelProvider provider, String modelName) {
        String url = resolveUrl(provider);
        if (url == null) {
            log.warn("Provider {} has no usable base URL", provider.getId());
            return ProbeResult.failure(ProbeResult.NETWORK_UNREACHABLE);
        }

        String apiKey;
        try {
            apiKey = cryptoService.decrypt(provider.getApiKeyCipher());
        } catch (RuntimeException e) {
            // 解不开的密钥必然无法通过认证；重新录入密钥即可修复，不必反复重试。
            log.warn("Provider {} API key cannot be decrypted ({}), probe fails as AUTH_FAILED",
                    provider.getId(), e.getClass().getSimpleName());
            return ProbeResult.failure(ProbeResult.AUTH_FAILED);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(PROBE_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        // Ollama 既不需要也不接受 Bearer 鉴权：密钥只发给 OPENAI_COMPATIBLE 端点，
        // 绝不发往 OLLAMA 的基础地址，避免把凭证泄露给不该看到它的服务。
        if (provider.getType() == ProviderType.OPENAI_COMPATIBLE
                && apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Probe of provider {} was interrupted", provider.getId());
            return ProbeResult.failure(ProbeResult.NETWORK_UNREACHABLE);
        } catch (IOException e) {
            String errorCode = classify(e);
            log.warn("Probe of provider {} failed ({}): {}", provider.getId(), errorCode,
                    rootMessage(e));
            return ProbeResult.failure(errorCode);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            log.warn("Probe of provider {} was rejected with HTTP {}", provider.getId(), status);
            return ProbeResult.failure(ProbeResult.AUTH_FAILED);
        }
        if (status < 200 || status >= 300) {
            // 非 401/403 的非 2xx 在规格给定的四个错误码里没有对应项，只能归为 NETWORK_UNREACHABLE；
            // 真实状态码写进日志，运维仍能区分 404 与 500，而对外返回保持规格兼容。
            log.warn("Probe of provider {} returned HTTP {}", provider.getId(), status);
            return ProbeResult.failure(ProbeResult.NETWORK_UNREACHABLE);
        }

        List<String> remoteModels;
        try {
            remoteModels = parseModelNames(response.body(), provider.getType());
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Probe of provider {} returned an unparseable model list ({}): {}",
                    provider.getId(), e.getClass().getSimpleName(), rootMessage(e));
            return ProbeResult.failure(ProbeResult.NETWORK_UNREACHABLE);
        }

        if (modelName != null && !modelName.isBlank() && !remoteModels.contains(modelName)) {
            return new ProbeResult(false, ProbeResult.MODEL_NOT_FOUND, remoteModels);
        }
        return ProbeResult.success(remoteModels);
    }

    /**
     * 解析探测地址：{@code OLLAMA} 用 {@code {base}/api/tags}；{@code OPENAI_COMPATIBLE} 用
     * {@code {base}/v1/models}，基础地址已以 {@code /v1} 结尾时改用 {@code {base}/models}。
     * 末尾斜杠一律先去掉。
     *
     * @param provider 提供商
     * @return 探测地址；基础地址为空或全是斜杠时返回 {@code null}
     */
    static String resolveUrl(ModelProvider provider) {
        String baseUrl = provider.getBaseUrl();
        if (baseUrl == null) {
            return null;
        }
        String base = baseUrl.trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            return null;
        }
        if (provider.getType() == ProviderType.OLLAMA) {
            return base + "/api/tags";
        }
        return base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
    }

    /**
     * 沿异常原因链分类：超时优先于连接错误，其余一律视为网络不可达。
     *
     * <p>包级可见是为了让 {@code ProviderProbeClassifyTest} 能确定性地断言（不联网、不依赖时序）。</p>
     *
     * <p>JDK 中 {@code java.net.http.HttpConnectTimeoutException} 继承自 {@code HttpTimeoutException}
     * （已用 {@code javap} 核对 JDK 21），因此单靠 {@code HttpTimeoutException} 分支就已经把
     * <em>连接</em>超时归入 {@code TIMEOUT}，无需另写分支；{@code SocketTimeoutException} 并列在此，
     * 是为将来 JDK 抛出原始 socket 读超时留出兜底。</p>
     */
    static String classify(IOException e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < CAUSE_CHAIN_LIMIT; t = t.getCause(), depth++) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                // HttpTimeoutException 已覆盖其子类 HttpConnectTimeoutException
                return ProbeResult.TIMEOUT;
            }
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SSLException) {
                return ProbeResult.NETWORK_UNREACHABLE;
            }
        }
        return ProbeResult.NETWORK_UNREACHABLE;
    }

    /** 供日志使用的安全描述：异常类型 + 消息，绝不含请求头等敏感信息。 */
    private static String rootMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }

    /**
     * 从响应体中解析模型名，跳过缺名字或名字为空的条目。
     *
     * @param body 响应体
     * @param type 协议类型，决定读取 {@code models[].name} 还是 {@code data[].id}
     * @return 模型名列表，永不为 {@code null}
     * @throws JsonProcessingException 响应体不是合法 JSON 时抛出
     */
    private List<String> parseModelNames(String body, ProviderType type) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(body == null ? "" : body);
        boolean ollama = type == ProviderType.OLLAMA;
        JsonNode array = root.path(ollama ? "models" : "data");
        if (!array.isArray()) {
            throw new IllegalArgumentException(
                    "response has no " + (ollama ? "models" : "data") + " array");
        }
        String nameField = ollama ? "name" : "id";
        List<String> names = new ArrayList<>();
        for (JsonNode node : array) {
            JsonNode nameNode = node.get(nameField);
            if (nameNode == null || !nameNode.isTextual()) {
                continue;
            }
            String name = nameNode.asText().trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }
}
