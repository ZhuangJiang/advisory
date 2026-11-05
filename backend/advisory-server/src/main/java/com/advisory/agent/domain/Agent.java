package com.advisory.agent.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code agents} 表的一行：Agent 的当前配置及其版本计数。
 *
 * <p>由 JDBC 直接读取（见 {@code AgentRepository}）。当前行与 {@code currentVersion} 对应的快照在同一个
 * 事务里写入，因此 {@link #toSnapshot()} 与落库的版本行始终描述同一份配置，不会出现「行是新的、快照是旧的」。</p>
 */
public record Agent(
        long id,
        String name,
        String description,
        String systemPrompt,
        Long chatModelId,
        Long embeddingModelId,
        List<Long> kbIds,
        Map<Long, List<Long>> nodeScope,
        int maxRounds,
        int topK,
        BigDecimal temperature,
        boolean enabled,
        int currentVersion,
        Instant createdAt,
        Instant updatedAt) {

    public AgentSnapshot toSnapshot() {
        return new AgentSnapshot(name, systemPrompt, chatModelId, embeddingModelId, kbIds,
                nodeScope, maxRounds, topK, temperature);
    }
}
