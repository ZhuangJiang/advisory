package com.advisory.agent.api.dto;

import com.advisory.chat.orchestration.OrchestrationResult;
import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.util.List;

/**
 * The full trace of a trial run, returned in the response body only — a trial run writes nothing
 * (spec §12).
 *
 * @param version   which snapshot actually ran (resolved from the request, so a client that omitted
 *                  {@code version} can see what it got)
 * @param steps     every executed step, same shape as the persisted {@code reasoning_steps}
 * @param citations the evidence the run used, numbered exactly as the {@code [n]} in the answer
 * @param latencyMs wall-clock time of the whole run, for prompt-tuning comparison
 */
public record AgentTestRunResponse(int version, String answer, List<AgentTestRunStep> steps,
                                   List<RetrievedChunk> citations, boolean truncated, long latencyMs) {

    public static AgentTestRunResponse of(int version, OrchestrationResult result, long latencyMs) {
        return new AgentTestRunResponse(version, result.answer(),
                result.steps().stream().map(AgentTestRunStep::from).toList(),
                result.citations(), result.truncated(), latencyMs);
    }
}
