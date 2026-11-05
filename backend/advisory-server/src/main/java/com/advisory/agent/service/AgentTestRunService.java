package com.advisory.agent.service;

import com.advisory.agent.api.dto.AgentTestRunResponse;
import com.advisory.agent.domain.Agent;
import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.agent.repo.AgentRepository;
import com.advisory.chat.orchestration.ChatOrchestrator;
import com.advisory.chat.orchestration.OrchestrationResult;
import com.advisory.chat.orchestration.StepSink;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 试运行（{@code POST /api/agents/{id}/test}）。
 *
 * <p>与真实提问走同一套编排，只有两点不同：快照由调用方指定（默认当前版本），且<em>什么都不落库</em>——
 * 不建会话、不写消息、不存推理步骤（spec §12）。因此这里直接调用 {@link ChatOrchestrator}，
 * 而不是走 {@code ChatService}（后者总会创建会话）。</p>
 *
 * <p>步骤接收器用 {@link StepSink#NOOP}：响应是同步 JSON，{@link OrchestrationResult} 里已经带着每一步
 * 及其载荷，再收集一遍只是把结果复制一份。停用状态的 Agent 也允许试运行——{@code enabled} 管的是
 * 用户能否用它发起对话，而不是管理员能否在启用前先验一验。</p>
 */
@Service
public class AgentTestRunService {

    private static final int MAX_QUESTION_LENGTH = 8000;

    private final AgentRepository agents;
    private final AgentService agentService;
    private final ChatOrchestrator orchestrator;

    public AgentTestRunService(AgentRepository agents, AgentService agentService,
                               ChatOrchestrator orchestrator) {
        this.agents = agents;
        this.agentService = agentService;
        this.orchestrator = orchestrator;
    }

    /** 用指定版本的快照跑一次问答，不写任何数据。 */
    public AgentTestRunResponse run(Long agentId, String question, Integer version) {
        String trimmed = question == null ? "" : question.trim();
        if (trimmed.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "问题不能为空");
        }
        if (trimmed.length() > MAX_QUESTION_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "问题长度不能超过 " + MAX_QUESTION_LENGTH + " 个字符");
        }
        if (version != null && version < 1) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "版本号必须是正整数");
        }
        Agent agent = agents.findActiveById(agentId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent 不存在"));
        int resolvedVersion = version == null ? agent.currentVersion() : version;
        AgentSnapshot snapshot = agentService.resolveSnapshot(agentId, resolvedVersion);

        long startedAt = System.nanoTime();
        OrchestrationResult result = orchestrator.run(snapshot, trimmed, StepSink.NOOP);
        long latencyMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
        return AgentTestRunResponse.of(resolvedVersion, result, latencyMs);
    }
}
