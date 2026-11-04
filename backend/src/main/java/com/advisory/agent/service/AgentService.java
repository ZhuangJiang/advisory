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
 * Agent configuration: CRUD, automatic versioning and the cross-knowledge-base invariants.
 *
 * <p>Versioning (spec §9): creating an agent writes version 1, and every successful PATCH writes
 * {@code current_version + 1} <em>in the same transaction</em> as the live row. Creation is version
 * 1 on purpose — a conversation opened before the first edit already points at version 1, so that
 * snapshot has to exist for {@link #resolveSnapshot(Long, int)} to replay it. Versions are never
 * updated or deleted; the version list is read-only.</p>
 *
 * <p>Invariants enforced here (all as 400 {@code VALIDATION_FAILED}, before anything is written):
 * <ul>
 *   <li>{@code kbIds} is non-empty and every id exists;</li>
 *   <li>every referenced knowledge base has an embedding model and they are all the same — the
 *       shared model is written to {@code agents.embedding_model_id} for run-time drift detection;</li>
 *   <li>{@code nodeScope} keys are a subset of {@code kbIds} and every node id exists and belongs to
 *       the knowledge base it is filed under;</li>
 *   <li>{@code chatModelId} exists and is a {@code CHAT} model; the numeric ranges match the schema
 *       checks (maxRounds 1–6, topK 1–100, temperature 0–2).</li>
 * </ul>
 * A duplicate name is a 409; deleting an agent that already has conversations is a 409 (archive
 * them first), because conversations reference the agent for their locked version.</p>
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

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResponse<AgentResponse> list(int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        List<AgentResponse> items = agents.findPage(pageSize, pageIndex * pageSize).stream()
                .map(AgentResponse::from)
                .toList();
        return PageResponse.of(items, pageIndex, pageSize, agents.count());
    }

    @Transactional(readOnly = true)
    public AgentResponse get(Long id) {
        return AgentResponse.from(requireAgent(id));
    }

    @Transactional(readOnly = true)
    public List<AgentVersionResponse> listVersions(Long agentId) {
        requireAgent(agentId);
        return versions.findByAgentIdOrderByVersionDesc(agentId).stream()
                .map(AgentVersionResponse::from)
                .toList();
    }

    /**
     * The snapshot a conversation locked at {@code version}. Task 11's orchestrator consumes this; a
     * missing version means the stored conversation points at a snapshot that does not exist and is
     * reported as 404 rather than silently falling back to the live configuration.
     */
    @Transactional(readOnly = true)
    public AgentSnapshot resolveSnapshot(Long agentId, int version) {
        requireAgent(agentId);
        return versions.findSnapshot(agentId, version)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Agent version " + version + " not found"));
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public AgentResponse create(CreateAgentRequest body) {
        String name = validateName(body.name());
        if (agents.existsByName(name)) {
            throw new AppException(ErrorCode.CONFLICT, "An agent named '" + name + "' already exists");
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
            // ux_agents_name is the last line of defence against two concurrent creates
            throw new AppException(ErrorCode.CONFLICT, "An agent named '" + name + "' already exists");
        }
        versions.insert(id, 1, snapshot);
        return AgentResponse.from(requireAgent(id));
    }

    /**
     * One transaction: lock the row ({@code SELECT ... FOR UPDATE}), merge the patch onto the current
     * configuration, validate the merged result, then update the live row and append the new
     * version. A rejected patch writes nothing at all.
     */
    @Transactional
    public AgentResponse update(Long id, UpdateAgentRequest body) {
        Agent current = agents.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent not found"));

        String name = body.name() == null ? current.name() : validateName(body.name());
        if (!name.equals(current.name()) && agents.existsByNameExcluding(name, id)) {
            throw new AppException(ErrorCode.CONFLICT, "An agent named '" + name + "' already exists");
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
            throw new AppException(ErrorCode.CONFLICT, "An agent named '" + name + "' already exists");
        }
        versions.insert(id, newVersion, snapshot);
        return AgentResponse.from(requireAgent(id));
    }

    @Transactional
    public void delete(Long id) {
        requireAgent(id);
        if (agents.countConversations(id) > 0) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Agent has conversations and cannot be deleted; archive them first");
        }
        agents.deleteById(id); // agent_versions cascade
    }

    // ------------------------------------------------------------------ validation

    private Agent requireAgent(Long id) {
        return agents.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent not found"));
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
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "name must not contain control characters");
            }
        }
        return name;
    }

    private static String validateSystemPrompt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "systemPrompt must not be blank");
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
            throw new AppException(ErrorCode.VALIDATION_FAILED, "kbIds must not be empty");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long kbId : raw) {
            if (kbId == null || kbId <= 0) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "kbIds must contain positive ids");
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
                        "nodeScope keys must be knowledge base ids");
            }
            List<Long> ids = new ArrayList<>();
            if (nodeIds != null) {
                for (Long nodeId : nodeIds) {
                    if (nodeId == null || nodeId <= 0) {
                        throw new AppException(ErrorCode.VALIDATION_FAILED,
                                "nodeScope values must be node ids");
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
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chatModelId is required");
        }
        String capability = agents.findModelCapability(chatModelId)
                .orElseThrow(() -> new AppException(ErrorCode.VALIDATION_FAILED,
                        "chatModelId " + chatModelId + " does not exist"));
        if (!"CHAT".equals(capability)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "chatModelId " + chatModelId + " must reference a CHAT model");
        }
        return chatModelId;
    }

    /**
     * All knowledge bases must exist, must have an embedding model and must share it. The shared id
     * is what the agent stores, so retrieval can never compare vectors from two different models.
     */
    private Long resolveEmbeddingModel(List<Long> kbIds) {
        List<AgentRepository.KbEmbedding> rows = agents.findKbEmbeddings(kbIds);
        if (rows.size() != kbIds.size()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "one or more knowledge bases in kbIds do not exist");
        }
        Long shared = null;
        for (AgentRepository.KbEmbedding row : rows) {
            if (row.embeddingModelId() == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "knowledge base " + row.kbId() + " has no embedding model");
            }
            if (shared == null) {
                shared = row.embeddingModelId();
            } else if (!shared.equals(row.embeddingModelId())) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "all knowledge bases must share the same embedding model ("
                                + shared + " vs " + row.embeddingModelId() + ")");
            }
        }
        return shared;
    }

    private void validateNodeScope(List<Long> kbIds, Map<Long, List<Long>> nodeScope) {
        for (Map.Entry<Long, List<Long>> entry : nodeScope.entrySet()) {
            Long kbId = entry.getKey();
            if (!kbIds.contains(kbId)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "nodeScope references knowledge base " + kbId + " which is not in kbIds");
            }
            for (Long nodeId : entry.getValue()) {
                Long owner = agents.findNodeKbId(nodeId)
                        .orElseThrow(() -> new AppException(ErrorCode.VALIDATION_FAILED,
                                "node " + nodeId + " does not exist"));
                if (!owner.equals(kbId)) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            "node " + nodeId + " does not belong to knowledge base " + kbId);
                }
            }
        }
    }

    private static int validateMaxRounds(int maxRounds) {
        if (maxRounds < MIN_MAX_ROUNDS || maxRounds > MAX_MAX_ROUNDS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "maxRounds must be between " + MIN_MAX_ROUNDS + " and " + MAX_MAX_ROUNDS);
        }
        return maxRounds;
    }

    private static int validateTopK(int topK) {
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "topK must be between 1 and " + MAX_TOP_K);
        }
        return topK;
    }

    /** Rounds to the column's scale so {@code numeric(3,2)} cannot silently round it afterwards. */
    private static BigDecimal validateTemperature(BigDecimal raw) {
        BigDecimal temperature = raw.setScale(2, RoundingMode.HALF_UP);
        if (temperature.signum() < 0 || temperature.compareTo(MAX_TEMPERATURE) > 0) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "temperature must be between 0 and 2");
        }
        return temperature;
    }
}
