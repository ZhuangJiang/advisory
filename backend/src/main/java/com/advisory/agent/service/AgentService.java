package com.advisory.agent.service;

import com.advisory.agent.api.dto.AgentResponse;
import com.advisory.agent.api.dto.AgentVersionResponse;
import com.advisory.agent.api.dto.CreateAgentRequest;
import com.advisory.agent.api.dto.UpdateAgentRequest;
import com.advisory.agent.domain.Agent;
import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.agent.repo.AgentRepository;
import com.advisory.agent.repo.AgentVersionRepository;
import com.advisory.common.api.PageResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Agent 配置：增删改查、自动版本化，以及跨知识库的一致性约束。
 *
 * <p>版本策略（spec §9）：新建时写入版本 1，此后每次成功的 PATCH 都在<em>同一个事务</em>里同时更新当前行并
 * 追加 {@code current_version + 1}。新建即版本 1 是有意为之——第一次编辑之前就已经打开的会话指向版本 1，
 * 这个快照必须存在，{@link #resolveSnapshot(Long, int)} 才能重放它。版本只追加，不更新也不删除，
 * 版本列表是只读的。</p>
 *
 * <p>在此集中校验的约束（全部在写入之前以 400 {@code VALIDATION_FAILED} 拒绝）：
 * <ul>
 *   <li>{@code kbIds} 非空，且每个 id 都真实存在；</li>
 *   <li>被引用的知识库都必须已配置向量模型，且模型相同——这个共同模型会写入
 *       {@code agents.embedding_model_id}，用于运行时发现配置漂移；</li>
 *   <li>{@code nodeScope} 的键必须是 {@code kbIds} 的子集，每个节点 id 存在且确实属于它所挂载的那个知识库；</li>
 *   <li>{@code chatModelId} 存在且能力为 {@code CHAT}；数值范围与表约束一致
 *       （maxRounds 1–6、topK 1–100、temperature 0–2）。</li>
 * </ul>
 * 名称重复返回 409；删除仍有关联会话的 Agent 同样返回 409（需要先归档会话），因为会话要靠它锁定版本。</p>
 */
@Service
public class AgentService {

    private static final int MAX_NAME_LENGTH = 128;
    private static final int DEFAULT_MAX_ROUNDS = 3;
    private static final int MIN_MAX_ROUNDS = 1;
    private static final int MAX_MAX_ROUNDS = 6;
    private static final int DEFAULT_TOP_K = 20;
    private static final int MAX_TOP_K = 100;
    private static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.70");
    private static final BigDecimal MAX_TEMPERATURE = new BigDecimal("2");
    private static final int MAX_PAGE_SIZE = 100;

    private final AgentRepository agents;
    private final AgentVersionRepository versions;

    public AgentService(AgentRepository agents, AgentVersionRepository versions) {
        this.agents = agents;
        this.versions = versions;
    }

    // ------------------------------------------------------------------ 读操作

    /** 分页列出 Agent。 */
    @Transactional(readOnly = true)
    public PageResponse<AgentResponse> list(int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        List<AgentResponse> items = agents.findPage(pageSize, pageIndex * pageSize).stream()
                .map(AgentResponse::from)
                .toList();
        return PageResponse.of(items, pageIndex, pageSize, agents.count());
    }

    /** 读取 Agent 详情。 */
    @Transactional(readOnly = true)
    public AgentResponse get(Long id) {
        return AgentResponse.from(requireAgent(id));
    }

    /** 按版本号倒序返回版本历史。 */
    @Transactional(readOnly = true)
    public List<AgentVersionResponse> listVersions(Long agentId) {
        requireAgent(agentId);
        return versions.findByAgentIdOrderByVersionDesc(agentId).stream()
                .map(AgentVersionResponse::from)
                .toList();
    }

    /**
     * 取出会话锁定的那个版本的快照，供 Task 11 的编排器使用。
     *
     * <p>版本缺失说明会话指向了一个并不存在的快照，此时报 404 而不是悄悄退回当前配置——否则同一次对话
     * 会在不同轮次用上不同的提示词，且没有任何痕迹可查。</p>
     *
     * @param agentId Agent 主键
     * @param version 版本号
     * @return 该版本的快照
     */
    @Transactional(readOnly = true)
    public AgentSnapshot resolveSnapshot(Long agentId, int version) {
        requireAgent(agentId);
        return versions.findSnapshot(agentId, version)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Agent 版本 " + version + " 不存在"));
    }

    // ------------------------------------------------------------------ 写操作

    /** 新建 Agent 并写入版本 1。 */
    @Transactional
    public AgentResponse create(CreateAgentRequest body) {
        String name = validateName(body.name());
        if (agents.existsByName(name)) {
            throw new AppException(ErrorCode.CONFLICT, "名为 '" + name + "' 的 Agent 已存在");
        }
        String systemPrompt = validateSystemPrompt(body.systemPrompt());
        String description = normalizeDescription(body.description());
        Long chatModelId = validateChatModel(body.chatModelId());
        List<Long> kbIds = normalizeKbIds(body.kbIds());
        Map<Long, List<Long>> nodeScope = normalizeNodeScope(body.nodeScope());
        Long embeddingModelId = resolveEmbeddingModel(kbIds);
        validateNodeScope(kbIds, nodeScope);
        int maxRounds = validateMaxRounds(body.maxRounds() == null ? DEFAULT_MAX_ROUNDS : body.maxRounds());
        int topK = validateTopK(body.topK() == null ? DEFAULT_TOP_K : body.topK());
        BigDecimal temperature = validateTemperature(
                body.temperature() == null ? DEFAULT_TEMPERATURE : body.temperature());
        boolean enabled = body.enabled() == null || body.enabled();

        AgentSnapshot snapshot = new AgentSnapshot(name, systemPrompt, chatModelId, embeddingModelId,
                kbIds, nodeScope, maxRounds, topK, temperature);
        long id;
        try {
            id = agents.insert(snapshot, description, enabled);
        } catch (DuplicateKeyException e) {
            // ux_agents_name 是并发新建同名 Agent 的最后一道防线
            throw new AppException(ErrorCode.CONFLICT, "名为 '" + name + "' 的 Agent 已存在");
        }
        versions.insert(id, 1, snapshot);
        return AgentResponse.from(requireAgent(id));
    }

    /**
     * 单个事务内完成：锁行（{@code SELECT ... FOR UPDATE}）→ 把补丁合并到当前配置 → 校验合并结果 →
     * 更新当前行并追加新版本。
     *
     * <p>先合并再校验，保证「只改一个字段」的请求也会按最终状态检查所有约束；校验不通过则一行都不写。</p>
     */
    @Transactional
    public AgentResponse update(Long id, UpdateAgentRequest body) {
        Agent current = agents.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent 不存在"));

        String name = body.name() == null ? current.name() : validateName(body.name());
        if (!name.equals(current.name()) && agents.existsByNameExcluding(name, id)) {
            throw new AppException(ErrorCode.CONFLICT, "名为 '" + name + "' 的 Agent 已存在");
        }
        String systemPrompt = body.systemPrompt() == null
                ? current.systemPrompt()
                : validateSystemPrompt(body.systemPrompt());
        String description = body.description() == null
                ? current.description()
                : normalizeDescription(body.description());
        Long chatModelId = body.chatModelId() == null
                ? current.chatModelId()
                : validateChatModel(body.chatModelId());
        List<Long> kbIds = body.kbIds() == null ? current.kbIds() : normalizeKbIds(body.kbIds());
        Map<Long, List<Long>> nodeScope = body.nodeScope() == null
                ? current.nodeScope()
                : normalizeNodeScope(body.nodeScope());

        Long embeddingModelId = resolveEmbeddingModel(kbIds);
        validateNodeScope(kbIds, nodeScope);
        int maxRounds = body.maxRounds() == null
                ? current.maxRounds()
                : validateMaxRounds(body.maxRounds());
        int topK = body.topK() == null ? current.topK() : validateTopK(body.topK());
        BigDecimal temperature = body.temperature() == null
                ? current.temperature()
                : validateTemperature(body.temperature());
        boolean enabled = body.enabled() == null ? current.enabled() : body.enabled();

        int newVersion = current.currentVersion() + 1;
        AgentSnapshot snapshot = new AgentSnapshot(name, systemPrompt, chatModelId, embeddingModelId,
                kbIds, nodeScope, maxRounds, topK, temperature);
        try {
            agents.update(id, snapshot, description, enabled, newVersion);
        } catch (DuplicateKeyException e) {
            throw new AppException(ErrorCode.CONFLICT, "名为 '" + name + "' 的 Agent 已存在");
        }
        versions.insert(id, newVersion, snapshot);
        return AgentResponse.from(requireAgent(id));
    }

    /** 删除 Agent；存在关联会话时返回 409。 */
    @Transactional
    public void delete(Long id) {
        requireAgent(id);
        if (agents.countConversations(id) > 0) {
            throw new AppException(ErrorCode.CONFLICT,
                    "该 Agent 下已有会话，无法删除；请先归档会话");
        }
        agents.deleteById(id); // 版本行随级联清理
    }

    // ------------------------------------------------------------------ 校验

    private Agent requireAgent(Long id) {
        return agents.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent 不存在"));
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
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "名称不能包含控制字符");
            }
        }
        return name;
    }

    private static String validateSystemPrompt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "系统提示词不能为空");
        }
        return raw;
    }

    private static String normalizeDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<Long> normalizeKbIds(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "知识库 id 列表不能为空");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long kbId : raw) {
            if (kbId == null || kbId <= 0) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "知识库 id 必须是正整数");
            }
            unique.add(kbId);
        }
        return List.copyOf(unique);
    }

    private static Map<Long, List<Long>> normalizeNodeScope(Map<Long, List<Long>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> normalized = new LinkedHashMap<>();
        raw.forEach((kbId, nodeIds) -> {
            if (kbId == null || kbId <= 0) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "nodeScope 的键必须是知识库 id");
            }
            List<Long> ids = new ArrayList<>();
            if (nodeIds != null) {
                for (Long nodeId : nodeIds) {
                    if (nodeId == null || nodeId <= 0) {
                        throw new AppException(ErrorCode.VALIDATION_FAILED,
                                "nodeScope 的值必须是节点 id");
                    }
                    ids.add(nodeId);
                }
            }
            normalized.put(kbId, List.copyOf(ids));
        });
        return normalized;
    }

    private Long validateChatModel(Long chatModelId) {
        if (chatModelId == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "必须指定对话模型");
        }
        String capability = agents.findModelCapability(chatModelId)
                .orElseThrow(() -> new AppException(ErrorCode.VALIDATION_FAILED,
                        "对话模型 " + chatModelId + " 不存在"));
        if (!"CHAT".equals(capability)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "对话模型 " + chatModelId + " 必须是 CHAT 类型的模型");
        }
        return chatModelId;
    }

    /**
     * 所有知识库都必须存在、都已配置向量模型，并且共用同一个模型。
     *
     * <p>Agent 存下的就是这个共用 id；否则检索时会把两个不同模型产出的向量放在一起算相似度，
     * 结果既不报错也毫无意义。</p>
     */
    private Long resolveEmbeddingModel(List<Long> kbIds) {
        List<AgentRepository.KbEmbedding> rows = agents.findKbEmbeddings(kbIds);
        if (rows.size() != kbIds.size()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "kbIds 中有一个或多个知识库不存在");
        }
        Long shared = null;
        for (AgentRepository.KbEmbedding row : rows) {
            if (row.embeddingModelId() == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "知识库 " + row.kbId() + " 未配置嵌入模型");
            }
            if (shared == null) {
                shared = row.embeddingModelId();
            } else if (!shared.equals(row.embeddingModelId())) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "所有知识库必须使用同一个嵌入模型（" + shared + " 与 " + row.embeddingModelId() + " 不一致）");
            }
        }
        return shared;
    }

    private void validateNodeScope(List<Long> kbIds, Map<Long, List<Long>> nodeScope) {
        for (Map.Entry<Long, List<Long>> entry : nodeScope.entrySet()) {
            Long kbId = entry.getKey();
            if (!kbIds.contains(kbId)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "nodeScope 引用了不在 kbIds 中的知识库 " + kbId);
            }
            for (Long nodeId : entry.getValue()) {
                Long owner = agents.findNodeKbId(nodeId)
                        .orElseThrow(() -> new AppException(ErrorCode.VALIDATION_FAILED,
                                "节点 " + nodeId + " 不存在"));
                if (!owner.equals(kbId)) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            "节点 " + nodeId + " 不属于知识库 " + kbId);
                }
            }
        }
    }

    private static int validateMaxRounds(int maxRounds) {
        if (maxRounds < MIN_MAX_ROUNDS || maxRounds > MAX_MAX_ROUNDS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "最大轮次必须在 " + MIN_MAX_ROUNDS + " 到 " + MAX_MAX_ROUNDS + " 之间");
        }
        return maxRounds;
    }

    private static int validateTopK(int topK) {
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "topK 必须在 1 到 " + MAX_TOP_K + " 之间");
        }
        return topK;
    }

    /** 按列的精度先行取整，避免 {@code numeric(3,2)} 在落库时静默四舍五入，导致读回的值与校验时不一致。 */
    private static BigDecimal validateTemperature(BigDecimal raw) {
        BigDecimal temperature = raw.setScale(2, RoundingMode.HALF_UP);
        if (temperature.signum() < 0 || temperature.compareTo(MAX_TEMPERATURE) > 0) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "temperature 必须在 0 到 2 之间");
        }
        return temperature;
    }
}
