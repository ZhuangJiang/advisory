package com.advisory.agent.api.dto;

import com.advisory.chat.orchestration.ReasoningStepData;

/**
 * 试运行中的一步。
 *
 * <p>与落库版的 {@code ReasoningStepResponse} 相比少了 {@code createdAt}：本来就没有写入，
 * 自然没有行时间戳可报。</p>
 */
public record AgentTestRunStep(int round, String phase, String summary, String payload,
                               long latencyMs) {

    public static AgentTestRunStep from(ReasoningStepData step) {
        return new AgentTestRunStep(step.round(), step.phase().name(), step.summary(),
                step.payload(), step.latencyMs());
    }
}
