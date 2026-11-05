package com.advisory.chat.orchestration;

import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 一次问答运行的完整结果。
 *
 * @param answer    最终答案（Markdown，永不为 {@code null} 或空白）
 * @param steps     按执行顺序排列的全部步骤；落库的那一份才是权威数据
 * @param citations 累积并去重后的证据，其顺序即 ANSWER 提示词中的编号
 * @param truncated 运行是否提前结束（单步/整体超时，或连续两次结构化输出不可解析）；
 *                  为真时消息行会存成 {@code TRUNCATED}
 * @param usage     token 用量；网关未上报时为 0
 */
public record OrchestrationResult(String answer, List<ReasoningStepData> steps,
                                  List<RetrievedChunk> citations, boolean truncated, TokenUsage usage) {

    public OrchestrationResult {
        steps = List.copyOf(steps);
        citations = List.copyOf(citations);
    }
}
