package com.advisory.provider.service;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.provider.api.dto.CreateModelRequest;
import com.advisory.provider.api.dto.CreateProviderRequest;
import com.advisory.provider.api.dto.ImportModelSpec;
import com.advisory.provider.api.dto.ImportModelsRequest;
import com.advisory.provider.api.dto.ModelResponse;
import com.advisory.provider.api.dto.PatchModelRequest;
import com.advisory.provider.api.dto.PatchProviderRequest;
import com.advisory.provider.api.dto.ProviderResponse;
import com.advisory.provider.domain.ModelCapability;
import com.advisory.provider.domain.ModelConfig;
import com.advisory.provider.domain.ModelProvider;
import com.advisory.provider.domain.ProviderType;
import com.advisory.provider.repo.ModelConfigRepository;
import com.advisory.provider.repo.ModelProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider CRUD, local model CRUD and the remote import.
 *
 * <p>Listing is one {@code JdbcTemplate} query (per-row {@code modelCount} plus the cipher for
 * masking) — the mask is computed by decrypting and keeping only the first four characters, so the
 * ciphertext and the full key never leave this class. Deleting a provider or a model first checks
 * the {@code knowledge_bases.embedding_model_id} / {@code agents.chat_model_id} /
 * {@code agents.embedding_model_id} references and rejects with {@code MODEL_IN_USE}; the model
 * cascade itself is the database's {@code ON DELETE CASCADE} on
 * {@code models.provider_id}.</p>
 *
 * <p>Imports are idempotent on {@code (provider_id, model_name)}: the whole request is validated
 * first (an invalid row inserts nothing), the remote listing is probed before anything is written,
 * then <em>existing rows are left untouched</em> — an import never overwrites an operator-edited
 * {@code dimension}, {@code params} or {@code enabled} — and only unknown names are inserted. Since
 * {@code V1__init.sql} has no unique index on that pair, the check-then-insert runs inside one
 * transaction.</p>
 */
@Service
public class ProviderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_URL_LENGTH = 512;
    private static final int MAX_MODEL_NAME_LENGTH = 255;
    private static final String MASK_SUFFIX = "****";
    private static final int MASK_PREFIX_LENGTH = 4;

    private static final String LIST_SQL = """
            SELECT p.id,
                   p.name,
                   p.type,
                   p.base_url,
                   p.enabled,
                   p.created_at,
                   p.api_key_cipher,
                   COALESCE(m.model_count, 0) AS model_count
            FROM model_providers p
            LEFT JOIN (
                SELECT provider_id, count(*) AS model_count FROM models GROUP BY provider_id
            ) m ON m.provider_id = p.id
            ORDER BY p.id
            """;

    /** References of a provider's models by knowledge bases and agents (either column). */
    private static final String PROVIDER_REFERENCE_SQL = """
            SELECT (SELECT count(*) FROM knowledge_bases
                    WHERE embedding_model_id IN (SELECT id FROM models WHERE provider_id = ?))
                 + (SELECT count(*) FROM agents
                    WHERE chat_model_id IN (SELECT id FROM models WHERE provider_id = ?)
                       OR embedding_model_id IN (SELECT id FROM models WHERE provider_id = ?))
            """;

    /** References of one model by knowledge bases and agents (either column). */
    private static final String MODEL_REFERENCE_SQL = """
            SELECT (SELECT count(*) FROM knowledge_bases WHERE embedding_model_id = ?)
                 + (SELECT count(*) FROM agents
                    WHERE chat_model_id = ? OR embedding_model_id = ?)
            """;

    private final ModelProviderRepository providers;
    private final ModelConfigRepository models;
    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;
    private final ProviderProbe probe;
    private final int expectedDimension;

    public ProviderService(ModelProviderRepository providers,
                           ModelConfigRepository models,
                           JdbcTemplate jdbc,
                           CryptoService cryptoService,
                           ProviderProbe probe,
                           @Value("${app.embedding.dimension}") int expectedDimension) {
        this.providers = providers;
        this.models = models;
        this.jdbc = jdbc;
        this.cryptoService = cryptoService;
        this.probe = probe;
        this.expectedDimension = expectedDimension;
    }

    // ------------------------------------------------------------------ providers

    /** Every provider with its model count and masked key. Any authenticated user may call it. */
    @Transactional(readOnly = true)
    public List<ProviderResponse> list() {
        return jdbc.query(LIST_SQL, (rs, rowNum) -> {
            long id = rs.getLong("id");
            return new ProviderResponse(
                    id,
                    rs.getString("name"),
                    ProviderType.valueOf(rs.getString("type")),
                    rs.getString("base_url"),
                    rs.getBoolean("enabled"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getLong("model_count"),
                    mask(id, rs.getString("api_key_cipher")));
        });
    }

    @Transactional
    public ProviderResponse create(CreateProviderRequest body) {
        ModelProvider provider = new ModelProvider(
                validateName(body.name()), body.type(), validateBaseUrl(body.baseUrl()));
        provider.setApiKeyCipher(cryptoService.encrypt(body.apiKey()));
        provider = providers.save(provider);
        return toResponse(provider);
    }

    @Transactional
    public ProviderResponse update(Long id, PatchProviderRequest body) {
        ModelProvider provider = find(id);
        if (body.name() != null) {
            provider.setName(validateName(body.name()));
        }
        if (body.type() != null) {
            provider.setType(body.type());
        }
        if (body.baseUrl() != null) {
            provider.setBaseUrl(validateBaseUrl(body.baseUrl()));
        }
        if (body.enabled() != null) {
            provider.setEnabled(body.enabled());
        }
        if (body.apiKey() != null) {
            // "" clears the key; anything else is re-encrypted; absent leaves it untouched.
            provider.setApiKeyCipher(body.apiKey().isEmpty()
                    ? null
                    : cryptoService.encrypt(body.apiKey()));
        }
        provider = providers.save(provider);
        return toResponse(provider);
    }

    @Transactional
    public void delete(Long id) {
        ModelProvider provider = find(id);
        Long references = jdbc.queryForObject(PROVIDER_REFERENCE_SQL, Long.class,
                provider.getId(), provider.getId(), provider.getId());
        if (references != null && references > 0) {
            throw new AppException(ErrorCode.MODEL_IN_USE,
                    "Provider has models referenced by a knowledge base or an agent");
        }
        // models go with it through the ON DELETE CASCADE on models.provider_id
        providers.delete(provider);
    }

    // ------------------------------------------------------------------ probing

    /** {@code POST /api/providers/{id}/test}: live probe of the provider (optional model name). */
    public ProbeResult test(Long providerId, String modelName) {
        return probe.test(find(providerId), modelName);
    }

    /** {@code GET /api/providers/{id}/models?remote=true}: probe outcome with the live names. */
    public ProbeResult remoteModels(Long providerId) {
        return probe.test(find(providerId), null);
    }

    // ------------------------------------------------------------------ local models

    @Transactional(readOnly = true)
    public List<ModelResponse> listModels(Long providerId) {
        find(providerId); // 404 for unknown providers
        return models.findByProviderIdOrderByIdAsc(providerId).stream()
                .map(ModelResponse::from)
                .toList();
    }

    @Transactional
    public ModelResponse createModel(Long providerId, CreateModelRequest body) {
        find(providerId);
        String modelName = validateModelName(body.modelName());
        validateDimension(body.capability(), body.dimension());
        assertNotDuplicate(providerId, modelName, null);
        ModelConfig model = models.save(
                new ModelConfig(providerId, modelName, body.capability(), body.dimension()));
        return ModelResponse.from(model);
    }

    @Transactional
    public ModelResponse updateModel(Long id, PatchModelRequest body) {
        ModelConfig model = findModel(id);
        if (body.modelName() != null) {
            String modelName = validateModelName(body.modelName());
            assertNotDuplicate(model.getProviderId(), modelName, model.getId());
            model.setModelName(modelName);
        }
        if (body.dimension() != null) {
            // the 1024 rule follows the row's capability, not a request-declared one
            validateDimension(model.getCapability(), body.dimension());
            model.setDimension(body.dimension());
        }
        if (body.enabled() != null) {
            model.setEnabled(body.enabled());
        }
        model = models.save(model);
        return ModelResponse.from(model);
    }

    @Transactional
    public void deleteModel(Long id) {
        ModelConfig model = findModel(id);
        Long references = jdbc.queryForObject(MODEL_REFERENCE_SQL, Long.class, id, id, id);
        if (references != null && references > 0) {
            throw new AppException(ErrorCode.MODEL_IN_USE,
                    "Model is referenced by a knowledge base or an agent");
        }
        models.delete(model);
    }

    // ------------------------------------------------------------------ import

    /**
     * {@code POST /api/providers/{id}/models/import} — probe first, validate everything, then insert
     * only unknown names. On probe failure: 404 for {@code MODEL_NOT_FOUND}, otherwise 400; the
     * probe's own error code is carried in the message. Any invalid row aborts the whole import
     * (nothing inserted). Returns the resulting local list.
     */
    @Transactional
    public List<ModelResponse> importModels(Long providerId, ImportModelsRequest body) {
        ModelProvider provider = find(providerId);

        ProbeResult probeResult = probe.test(provider, null);
        if (!probeResult.ok()) {
            ErrorCode errorCode = ProbeResult.MODEL_NOT_FOUND.equals(probeResult.errorCode())
                    ? ErrorCode.RESOURCE_NOT_FOUND
                    : ErrorCode.VALIDATION_FAILED;
            throw new AppException(errorCode,
                    "Provider probe failed: " + probeResult.errorCode());
        }

        // Validate every requested row before touching the table: a single bad embedding
        // dimension must leave the table exactly as it was.
        Map<String, ImportModelSpec> byName = new LinkedHashMap<>();
        List<ImportModelSpec> specs = body.models() == null ? List.of() : body.models();
        for (ImportModelSpec spec : specs) {
            if (spec == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "models entries must not be null");
            }
            String modelName = validateModelName(spec.modelName());
            validateDimension(spec.capability(), spec.dimension());
            byName.putIfAbsent(modelName, spec);
        }

        for (Map.Entry<String, ImportModelSpec> entry : byName.entrySet()) {
            // No unique index on (provider_id, model_name): check-then-insert inside this
            // transaction. Existing rows are left unchanged (choice stated in the task report);
            // only unknown names are inserted.
            if (!models.findByProviderIdAndModelName(providerId, entry.getKey()).isEmpty()) {
                continue;
            }
            ImportModelSpec spec = entry.getValue();
            models.save(new ModelConfig(providerId, entry.getKey(),
                    spec.capability(), spec.dimension()));
        }

        return models.findByProviderIdOrderByIdAsc(providerId).stream()
                .map(ModelResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * First four characters of the decrypted key plus {@code ****}; {@code null} when there is no
     * key or it cannot be decrypted (logged without any key material — a broken cipher must never
     * fail the listing).
     */
    private String mask(long providerId, String apiKeyCipher) {
        if (apiKeyCipher == null || apiKeyCipher.isBlank()) {
            return null;
        }
        try {
            String key = cryptoService.decrypt(apiKeyCipher);
            if (key == null || key.isEmpty()) {
                return null;
            }
            return key.substring(0, Math.min(MASK_PREFIX_LENGTH, key.length())) + MASK_SUFFIX;
        } catch (RuntimeException e) {
            log.warn("Provider {} API key cannot be decrypted; masking skipped ({})",
                    providerId, e.getClass().getSimpleName());
            return null;
        }
    }

    private ProviderResponse toResponse(ModelProvider provider) {
        long modelCount = models.countByProviderId(provider.getId());
        return new ProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getType(),
                provider.getBaseUrl(),
                provider.isEnabled(),
                provider.getCreatedAt(),
                modelCount,
                mask(provider.getId(), provider.getApiKeyCipher()));
    }

    /** The single gate for the 1024 rule: EMBEDDING must equal the configured dimension. */
    private void validateDimension(ModelCapability capability, Integer dimension) {
        if (capability == ModelCapability.EMBEDDING) {
            if (dimension == null || dimension != expectedDimension) {
                throw new AppException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                        "EMBEDDING model dimension must be " + expectedDimension
                                + ", got " + dimension);
            }
        } else if (dimension != null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "CHAT model must not specify a dimension");
        }
    }

    /**
     * The pair has no unique index in the schema, so duplicates are rejected by a query — always
     * called inside the write transaction that then inserts (check-then-insert).
     */
    private void assertNotDuplicate(Long providerId, String modelName, Long excludingModelId) {
        List<ModelConfig> existing = models.findByProviderIdAndModelName(providerId, modelName);
        boolean duplicate = existing.stream()
                .anyMatch(candidate -> excludingModelId == null
                        || !excludingModelId.equals(candidate.getId()));
        if (duplicate) {
            throw new AppException(ErrorCode.CONFLICT,
                    "A model named '" + modelName + "' already exists for this provider");
        }
    }

    private ModelProvider find(Long id) {
        return providers.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Provider not found"));
    }

    private ModelConfig findModel(Long id) {
        return models.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Model not found"));
    }

    private static String validateName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not be blank");
        }
        String name = raw.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    private static String validateBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "baseUrl must not be blank");
        }
        String baseUrl = raw.trim();
        if (baseUrl.length() > MAX_URL_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "baseUrl must not exceed " + MAX_URL_LENGTH + " characters");
        }
        return baseUrl;
    }

    private static String validateModelName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "modelName must not be blank");
        }
        String modelName = raw.trim();
        if (modelName.length() > MAX_MODEL_NAME_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "modelName must not exceed " + MAX_MODEL_NAME_LENGTH + " characters");
        }
        return modelName;
    }
}
