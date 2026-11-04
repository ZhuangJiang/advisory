package com.advisory.chat.api.dto;

import com.advisory.chat.domain.ReasoningStep;

import java.time.Instant;

/** One persisted reasoning step; {@code payload} is the step's JSON as produced by the run. */
public record ReasoningStepResponse(int round, String phase, String summary, String payload,
                                    long latencyMs, Instant createdAt) {

    public static ReasoningStepResponse from(ReasoningStep step) {
        return new ReasoningStepResponse(step.round(), step.phase().name(), step.summary(),
                step.payload(), step.latencyMs(), step.createdAt());
    }
}
