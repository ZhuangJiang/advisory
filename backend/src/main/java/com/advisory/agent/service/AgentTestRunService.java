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
 * Trial runs ({@code POST /api/agents/{id}/test}).
 *
 * <p>A trial run is the same orchestration as a real question with two differences: the snapshot is
 * chosen by the caller (default: current version) and <em>nothing is persisted</em> — no
 * conversation, no message, no reasoning steps (spec §12). It therefore talks to
 * {@link ChatOrchestrator} directly rather than through {@code ChatService}, which would create a
 * thread.</p>
 *
 * <p>The sink is {@link StepSink#NOOP}: the response is synchronous JSON and
 * {@link OrchestrationResult} already carries every step with its payload, so a collecting sink
 * would only duplicate the result. A disabled agent may still be trialled — {@code enabled} governs
 * whether users can start conversations with it, not whether an administrator may check it before
 * switching it on.</p>
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

    public AgentTestRunResponse run(Long agentId, String question, Integer version) {
        String trimmed = question == null ? "" : question.trim();
        if (trimmed.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "question must not be blank");
        }
        if (trimmed.length() > MAX_QUESTION_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "question must not exceed " + MAX_QUESTION_LENGTH + " characters");
        }
        if (version != null && version < 1) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "version must be a positive integer");
        }
        Agent agent = agents.findById(agentId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Agent not found"));
        int resolvedVersion = version == null ? agent.currentVersion() : version;
        AgentSnapshot snapshot = agentService.resolveSnapshot(agentId, resolvedVersion);

        long startedAt = System.nanoTime();
        OrchestrationResult result = orchestrator.run(snapshot, trimmed, StepSink.NOOP);
        long latencyMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
        return AgentTestRunResponse.of(resolvedVersion, result, latencyMs);
    }
}
