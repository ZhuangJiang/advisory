package com.advisory.agent.api.dto;

import com.advisory.chat.orchestration.OrchestrationResult;
import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 试运行的完整轨迹，只存在于响应体中——试运行不写任何数据（spec §12）。
 *
 * @param version   实际运行的快照版本（由请求解析而来，未传 {@code version} 的客户端也能看到结果）
 * @param steps     执行过的每一步，结构与落库的 {@code reasoning_steps} 一致
 * @param citations 本次使用的证据，编号与答案中的 {@code [n]} 完全对应
 * @param latencyMs 整次运行的耗时，用于对比不同提示词的效果
 */
public record AgentTestRunResponse(int version, String answer, List<AgentTestRunStep> steps,
                                   List<RetrievedChunk> citations, boolean truncated, long latencyMs) {

    public static AgentTestRunResponse of(int version, OrchestrationResult result, long latencyMs) {
        return new AgentTestRunResponse(version, result.answer(),
                result.steps().stream().map(AgentTestRunStep::from).toList(),
                result.citations(), result.truncated(), latencyMs);
    }
}
