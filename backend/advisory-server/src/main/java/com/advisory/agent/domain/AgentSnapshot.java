package com.advisory.agent.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 在某一时刻的不可变配置。
 *
 * <p>这个 record <em>就是</em> {@code agent_versions.snapshot} 里存的那份 JSON，也是当前 {@code agents} 行
 * 与所有历史版本共同收敛到的结构，因此会话总能以它启动时的设置被完整重放。{@code embeddingModelId}
 * 从不接受客户端传入：它由知识库推导并冗余存储，用于在运行时发现配置漂移（spec §9）。</p>
 *
 * <p>{@code nodeScope} 把知识库 id 映射到限定的节点 id 列表；列表为空表示「整个知识库」。规范构造器会
 * 防御性地复制两个集合，避免调用方改动一个刚从数据库读出的快照，却让版本内容在暗处发生变化。</p>
 */
public record AgentSnapshot(
        String name,
        String systemPrompt,
        Long chatModelId,
        Long embeddingModelId,
        List<Long> kbIds,
        Map<Long, List<Long>> nodeScope,
        int maxRounds,
        int topK,
        BigDecimal temperature) {

    public AgentSnapshot {
        kbIds = kbIds == null ? List.of() : List.copyOf(kbIds);
        nodeScope = immutableCopy(nodeScope);
    }

    private static Map<Long, List<Long>> immutableCopy(Map<Long, List<Long>> scope) {
        if (scope == null || scope.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> copy = new LinkedHashMap<>();
        scope.forEach((kbId, nodeIds) ->
                copy.put(kbId, nodeIds == null ? List.of() : List.copyOf(nodeIds)));
        return Collections.unmodifiableMap(copy);
    }
}
