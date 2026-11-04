package com.advisory.chat.orchestration;

/**
 * One persisted reasoning step ({@code reasoning_steps}). {@code payload} is the step's JSON (the
 * structured PLAN/REFLECT output, the retrieval queries, or a small ANSWER marker), which is what
 * makes a past run explainable without replaying the model.
 */
public record ReasoningStepData(int round, Phase phase, String summary, String payload, long latencyMs) {
}
