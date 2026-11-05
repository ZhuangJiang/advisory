package com.advisory.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/agents} 的请求体。
 *
 * <p>刻意不接收 {@code embeddingModelId}：它由所引用知识库的向量模型推导得出，否则客户端就能写进一个与
 * 知识库不一致的值，导致检索时拿两套向量做相似度比较。可选字段留空时取表默认值
 * （默认值依次为 maxRounds=3、topK=20、temperature=0.70、enabled=true）。</p>
 */
public record CreateAgentRequest(
        @NotBlank @Size(max = 128) String name,
        String description,
        @NotBlank String systemPrompt,
        @NotNull Long chatModelId,
        @NotEmpty List<Long> kbIds,
        Map<Long, List<Long>> nodeScope,
        Integer maxRounds,
        Integer topK,
        BigDecimal temperature,
        Boolean enabled) {
}
