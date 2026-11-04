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
 * Live connectivity check for one {@link ModelProvider} using the JDK {@link HttpClient} (no extra
 * dependency).
 *
 * <p><b>URL normalization:</b> the base URL is stripped of trailing slashes; {@code OLLAMA} calls
 * {@code {base}/api/tags} while {@code OPENAI_COMPATIBLE} calls {@code {base}/v1/models}, unless
 * the base already ends with {@code /v1}, in which case {@code {base}/models} is used. When the
 * provider has a key, the decrypted value is sent as {@code Authorization: Bearer …} — the key is
 * never logged.</p>
 *
 * <p><b>Error mapping</b> (10s connect and request timeout): 401/403 → {@code AUTH_FAILED};
 * {@code UnknownHostException} / {@code ConnectException} / {@code SSLException} →
 * {@code NETWORK_UNREACHABLE}; {@code HttpTimeoutException} → {@code TIMEOUT}; any other non-2xx
 * status (logged with the status) or unparseable body → {@code NETWORK_UNREACHABLE}. Ollama names
 * come from {@code models[].name}, OpenAI names from {@code data[].id}. A requested model missing
 * from a successful listing → {@code MODEL_NOT_FOUND}.</p>
 */
@Component
public class ProviderProbe {

    private static final Logger log = LoggerFactory.getLogger(ProviderProbe.class);

    /** Spec-mandated probe timeout for both connecting and the request itself. */
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
     * Probes {@code provider}; when {@code modelName} is non-blank it must appear in the remote
     * listing or the probe reports {@code MODEL_NOT_FOUND}. Never throws for provider-side
     * problems — every failure comes back as {@code ok=false} plus one of the four error codes.
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
            // A key that cannot be decrypted can never authenticate; re-entering the key fixes it.
            log.warn("Provider {} API key cannot be decrypted ({}), probe fails as AUTH_FAILED",
                    provider.getId(), e.getClass().getSimpleName());
            return ProbeResult.failure(ProbeResult.AUTH_FAILED);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(PROBE_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        // Ollama neither expects nor wants bearer authentication: the key is only ever sent to
        // OPENAI_COMPATIBLE endpoints, never to an OLLAMA base URL.
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
            // Non-401/403 non-2xx has no dedicated code among the four spec codes, so it maps to
            // NETWORK_UNREACHABLE; the actual status is logged so the operator can still tell a
            // 404 from a 500 while the envelope stays spec-compliant.
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
     * {@code OLLAMA}: {@code {base}/api/tags}. {@code OPENAI_COMPATIBLE}: {@code {base}/v1/models},
     * or {@code {base}/models} when the base already ends with {@code /v1}. Trailing slashes are
     * always stripped first. {@code null} when there is no usable base.
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
     * Walks the cause chain: timeout beats connection errors, everything else is unreachable.
     * Package-private for the deterministic {@code ProviderProbeClassifyTest} (no network, no
     * timing).
     *
     * <p>In the JDK, {@code java.net.http.HttpConnectTimeoutException extends HttpTimeoutException}
     * (verified with {@code javap} against JDK 21), so the single {@code HttpTimeoutException}
     * branch already classifies <em>connect</em> timeouts as {@code TIMEOUT} — no separate branch
     * is needed for them. {@code SocketTimeoutException} sits alongside it for a raw socket read
     * timeout should the JDK ever surface one.</p>
     */
    static String classify(IOException e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < CAUSE_CHAIN_LIMIT; t = t.getCause(), depth++) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                // HttpTimeoutException covers HttpConnectTimeoutException (its subclass)
                return ProbeResult.TIMEOUT;
            }
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SSLException) {
                return ProbeResult.NETWORK_UNREACHABLE;
            }
        }
        return ProbeResult.NETWORK_UNREACHABLE;
    }

    /** A safe one-line description for logs: exception type + message, never request headers. */
    private static String rootMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }

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
