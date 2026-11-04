package com.advisory.chat.api.dto;

import com.advisory.chat.domain.ReasoningStep;

import java.time.Instant;

/** 一条已落库的推理步骤；{@code payload} 是运行时原样产出的步骤 JSON。 */
public record ReasoningStepResponse(int round, String phase, String summary, String payload,
                                    long latencyMs, Instant createdAt) {

    public static ReasoningStepResponse from(ReasoningStep step) {
        return new ReasoningStepResponse(step.round(), step.phase().name(), step.summary(),
                step.payload(), step.latencyMs(), step.createdAt());
    }
}
