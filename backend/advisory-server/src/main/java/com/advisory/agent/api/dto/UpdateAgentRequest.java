package com.advisory.agent.api.dto;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code PATCH /api/agents/{id}} 的请求体：所有字段可选，{@code null} 表示保持不变。
 *
 * <p>{@code description} 可以传空串来清空（服务端会归一化为 {@code null}）。{@code embeddingModelId}
 * 会按更新后的 {@code kbIds} 重新推导，因此它不在本请求中，传了也不会生效。启停状态由
 * {@code POST /api/agents/{id}/online|offline} 维护，不属于配置补丁。</p>
 */
public record UpdateAgentRequest(
        @Size(max = 128) String name,
        String description,
        String systemPrompt,
        Long chatModelId,
        List<Long> kbIds,
        Map<Long, List<Long>> nodeScope,
        Integer maxRounds,
        Integer topK,
        BigDecimal temperature) {
}
