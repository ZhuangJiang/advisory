package com.advisory.agent.api.dto;

import com.advisory.chat.orchestration.ReasoningStepData;

/**
 * One step of a trial run. Unlike the persisted {@code ReasoningStepResponse} it has no
 * {@code createdAt}: nothing was written, so there is no row timestamp to report.
 */
public record AgentTestRunStep(int round, String phase, String summary, String payload,
                               long latencyMs) {

    public static AgentTestRunStep from(ReasoningStepData step) {
        return new AgentTestRunStep(step.round(), step.phase().name(), step.summary(),
                step.payload(), step.latencyMs());
    }
}
