package com.advisory.provider.service;

import com.advisory.common.api.PageResponse;
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供商增删改查、本地模型增删改查以及远端模型导入。
 *
 * <p>列表用一条 {@code JdbcTemplate} 查询取回（每行带 {@code modelCount} 与用于掩码的密文），掩码由解密后
 * 取前四位得到，因此密文与完整密钥都不会离开本类。删除提供商或模型前会先检查
 * {@code knowledge_bases.embedding_model_id}、{@code agents.chat_model_id}、
 * {@code agents.embedding_model_id} 的引用，被引用则返回 {@code MODEL_IN_USE}；
 * 模型随提供商一起删除这件事本身由数据库的 {@code models.provider_id ON DELETE CASCADE} 完成。</p>
 *
 * <p>导入以 {@code (provider_id, model_name)} 为幂等键：先整体校验请求（任一行非法则一行都不插入），
 * 再探测远端列表，最后<em>已存在的行保持原样</em>——导入绝不覆盖运维手工调整过的 {@code dimension}、
 * {@code params} 或 {@code enabled}——只插入未知的模型名。由于 {@code V1__init.sql} 对该组合没有唯一索引，
 * 「先查后插」必须跑在同一个事务里。</p>
 */
@Service
public class ProviderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_URL_LENGTH = 512;
    private static final int MAX_MODEL_NAME_LENGTH = 255;
    private static final String MASK_SUFFIX = "****";
    private static final int MASK_PREFIX_LENGTH = 4;
    private static final int MAX_PAGE_SIZE = 100;

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

    /** 统计某提供商下所有模型被知识库与 Agent 引用的次数（对话列与向量列都算）。 */
    private static final String PROVIDER_REFERENCE_SQL = """
            SELECT (SELECT count(*) FROM knowledge_bases
                    WHERE embedding_model_id IN (SELECT id FROM models WHERE provider_id = ?))
                 + (SELECT count(*) FROM agents
                    WHERE chat_model_id IN (SELECT id FROM models WHERE provider_id = ?)
                       OR embedding_model_id IN (SELECT id FROM models WHERE provider_id = ?))
            """;

    /** 统计单个模型被知识库与 Agent 引用的次数（对话列与向量列都算）。 */
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

    // ------------------------------------------------------------------ 提供商

    /**
     * 按主键升序分页列出提供商，附带模型数量与掩码后的密钥。
     *
     * <p>页码从 0 开始，页长默认 20、上限 100（与全局列表接口约定一致）；任意已登录用户都可调用，
     * 因为 Agent 配置页需要用这份列表做下拉选项。</p>
     *
     * @param page 页码
     * @param size 每页条数
     * @return 提供商分页数据
     */
    @Transactional(readOnly = true)
    public PageResponse<ProviderResponse> list(int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM model_providers", Long.class);
        long totalCount = total == null ? 0 : total;

        List<ProviderResponse> items = jdbc.query(
                LIST_SQL + " LIMIT ? OFFSET ?", this::mapProvider,
                pageSize, pageIndex * pageSize);
        return PageResponse.of(items, pageIndex, pageSize, totalCount);
    }

    private ProviderResponse mapProvider(ResultSet rs, int rowNum) throws SQLException {
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
    }

    /** 新建提供商，密钥加密后落库。 */
    @Transactional
    public ProviderResponse create(CreateProviderRequest body) {
        ModelProvider provider = new ModelProvider(
                validateName(body.name()), body.type(), validateBaseUrl(body.baseUrl()));
        provider.setApiKeyCipher(cryptoService.encrypt(body.apiKey()));
        provider = providers.save(provider);
        return toResponse(provider);
    }

    /** 局部更新提供商，按 apiKey 的三种语义处理密钥。 */
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
            // 传空串表示清空密钥；传其他值表示重新加密写入；字段缺省表示保持不变。
            provider.setApiKeyCipher(body.apiKey().isEmpty()
                    ? null
                    : cryptoService.encrypt(body.apiKey()));
        }
        provider = providers.save(provider);
        return toResponse(provider);
    }

    /** 删除提供商；其下模型由 ON DELETE CASCADE 连带删除。 */
    @Transactional
    public void delete(Long id) {
        ModelProvider provider = find(id);
        Long references = jdbc.queryForObject(PROVIDER_REFERENCE_SQL, Long.class,
                provider.getId(), provider.getId(), provider.getId());
        if (references != null && references > 0) {
            throw new AppException(ErrorCode.MODEL_IN_USE,
                    "该提供商下的模型正被知识库或 Agent 引用");
        }
        // 其下模型由 models.provider_id 上的 ON DELETE CASCADE 连带删除
        providers.delete(provider);
    }

    // ------------------------------------------------------------------ 探测

    /** {@code POST /api/providers/{id}/test}：对提供商做实时探测（可指定模型名）。 */
    public ProbeResult test(Long providerId, String modelName) {
        return probe.test(find(providerId), modelName);
    }

    /** {@code GET /api/providers/{id}/models?remote=true}：探测结果连同远端模型名一起返回。 */
    public ProbeResult remoteModels(Long providerId) {
        return probe.test(find(providerId), null);
    }

    // ------------------------------------------------------------------ 本地模型

    /** 列出某提供商的本地模型。 */
    @Transactional(readOnly = true)
    public List<ModelResponse> listModels(Long providerId) {
        find(providerId); // 提供商不存在时由这里抛 404
        return models.findByProviderIdOrderByIdAsc(providerId).stream()
                .map(ModelResponse::from)
                .toList();
    }

    /** 登记模型并校验维度规则。 */
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

    /** 更新模型；能力不可改，维度按库中能力重新校验。 */
    @Transactional
    public ModelResponse updateModel(Long id, PatchModelRequest body) {
        ModelConfig model = findModel(id);
        if (body.modelName() != null) {
            String modelName = validateModelName(body.modelName());
            assertNotDuplicate(model.getProviderId(), modelName, model.getId());
            model.setModelName(modelName);
        }
        if (body.dimension() != null) {
            // 维度规则以库中该行的 capability 为准，而不是请求里声明的那个
            validateDimension(model.getCapability(), body.dimension());
            model.setDimension(body.dimension());
        }
        if (body.enabled() != null) {
            model.setEnabled(body.enabled());
        }
        model = models.save(model);
        return ModelResponse.from(model);
    }

    /** 删除模型；仍被知识库或 Agent 引用时返回 409。 */
    @Transactional
    public void deleteModel(Long id) {
        ModelConfig model = findModel(id);
        Long references = jdbc.queryForObject(MODEL_REFERENCE_SQL, Long.class, id, id, id);
        if (references != null && references > 0) {
            throw new AppException(ErrorCode.MODEL_IN_USE,
                    "该模型正被知识库或 Agent 引用");
        }
        models.delete(model);
    }

    // ------------------------------------------------------------------ 导入

    /**
     * {@code POST /api/providers/{id}/models/import}：先探测、再整体校验，最后只插入未知的模型名。
     *
     * <p>探测失败时按错误码分流：{@code MODEL_NOT_FOUND} 返回 404，其余返回 400，并在消息里带上探测得到的
     * 错误码。任一行非法即整体放弃，一行也不写入。返回导入后的本地模型列表。</p>
     *
     * @param providerId 提供商主键
     * @param request    待导入的模型规格列表
     * @return 导入后的本地模型列表
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
                    "提供商探测失败：" + probeResult.errorCode());
        }

        // 先校验全部请求行再动表：只要有一行的向量维度不合法，表必须保持原样，不能留下半截导入。
        Map<String, ImportModelSpec> byName = new LinkedHashMap<>();
        List<ImportModelSpec> specs = body.models() == null ? List.of() : body.models();
        for (ImportModelSpec spec : specs) {
            if (spec == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "模型列表不能包含 null");
            }
            String modelName = validateModelName(spec.modelName());
            validateDimension(spec.capability(), spec.dimension());
            byName.putIfAbsent(modelName, spec);
        }

        for (Map.Entry<String, ImportModelSpec> entry : byName.entrySet()) {
            // (provider_id, model_name) 上没有唯一索引，只能在本事务内「先查后插」。
            // 已存在的行保持原样（任务报告里已说明这一取舍），只插入未知的模型名。
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

    // ------------------------------------------------------------------ 辅助方法

    /**
     * 掩码：解密后取前四位再加 {@code ****}。
     *
     * <p>无密钥或解不开时返回 {@code null}（日志里不含密钥材料）——一条坏密文不该让整个列表接口失败。</p>
     *
     * @param cipher 库中的密文
     * @return 掩码后的密钥，或 {@code null}
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

    /** 维度规则的唯一校验点：向量模型的维度必须等于配置的维度。 */
    private void validateDimension(ModelCapability capability, Integer dimension) {
        if (capability == ModelCapability.EMBEDDING) {
            if (dimension == null || dimension != expectedDimension) {
                throw new AppException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                        "嵌入模型的维度必须是 " + expectedDimension + "，实际为 " + dimension);
            }
        } else if (dimension != null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "CHAT 类型的模型不能指定维度");
        }
    }

    /**
     * 该组合在库中没有唯一索引，只能靠查询来判重——必须在随后的写入事务内调用（先查后插），
     * 否则并发下两个请求会同时通过检查。
     */
    private void assertNotDuplicate(Long providerId, String modelName, Long excludingModelId) {
        List<ModelConfig> existing = models.findByProviderIdAndModelName(providerId, modelName);
        boolean duplicate = existing.stream()
                .anyMatch(candidate -> excludingModelId == null
                        || !excludingModelId.equals(candidate.getId()));
        if (duplicate) {
            throw new AppException(ErrorCode.CONFLICT,
                    "该提供商下已存在名为 '" + modelName + "' 的模型");
        }
    }

    private ModelProvider find(Long id) {
        return providers.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "提供商不存在"));
    }

    private ModelConfig findModel(Long id) {
        return models.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "模型不存在"));
    }

    private static String validateName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "名称不能为空");
        }
        String name = raw.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "名称长度不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        return name;
    }

    private static String validateBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "接口地址不能为空");
        }
        String baseUrl = raw.trim();
        if (baseUrl.length() > MAX_URL_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "接口地址长度不能超过 " + MAX_URL_LENGTH + " 个字符");
        }
        return baseUrl;
    }

    private static String validateModelName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "模型名称不能为空");
        }
        String modelName = raw.trim();
        if (modelName.length() > MAX_MODEL_NAME_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "模型名称长度不能超过 " + MAX_MODEL_NAME_LENGTH + " 个字符");
        }
        return modelName;
    }
}
