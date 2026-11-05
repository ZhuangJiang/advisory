package com.advisory.chat.orchestration;

/**
 * 一条待落库的推理步骤（对应 {@code reasoning_steps}）。
 *
 * <p>{@code payload} 是该步骤的 JSON（PLAN/REFLECT 的结构化输出、检索语句，或 ANSWER 阶段的一个小标记），
 * 正是它让历史运行无需重放模型也能解释清楚。</p>
 */
public record ReasoningStepData(int round, Phase phase, String summary, String payload, long latencyMs) {
}
