package com.advisory.agent.api.dto;

import com.advisory.agent.domain.Agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent 在列表、详情、新建与更新接口中的返回体。
 *
 * <p>{@code currentVersion} 是当前生效的版本号，会话据此锁定自己使用的快照。</p>
 */
public record AgentResponse(
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

    public static AgentResponse from(Agent agent) {
        return new AgentResponse(
                agent.id(),
                agent.name(),
                agent.description(),
                agent.systemPrompt(),
                agent.chatModelId(),
                agent.embeddingModelId(),
                agent.kbIds(),
                agent.nodeScope(),
                agent.maxRounds(),
                agent.topK(),
                agent.temperature(),
                agent.enabled(),
                agent.currentVersion(),
                agent.createdAt(),
                agent.updatedAt());
    }
}
