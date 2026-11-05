package com.advisory.knowledge.retrieval;

import com.advisory.knowledge.ingest.EmbeddingGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 相关性下限：向量路径整轮最高分不达标时，这一路结果整体丢弃。
 *
 * <p>没有这道闸门，任何输入都能凑出 {@code topK} 条「最像的」片段——「你好」也能源源不断地拿到 8 段
 * 无关正文，编排器就会把噪声当证据、多跑两轮反思，最后基于噪声作答。</p>
 *
 * <p>用 mock 而不是 Testcontainers：这里验的是「分够不够就丢不丢」这段判断，不需要真向量。</p>
 */
class RetrievalServiceRelevanceFloorTest {

    private static final double FLOOR = 0.55;

    private RetrievalJdbcRepository repository;
    private EmbeddingGateway gateway;

    @BeforeEach
    void setUp() {
        repository = mock(RetrievalJdbcRepository.class);
        gateway = mock(EmbeddingGateway.class);
        when(repository.findKbMeta(anyList()))
                .thenReturn(Map.of(10L, new RetrievalJdbcRepository.KbMeta(2L, "READY")));
        when(repository.keywordTopK(anyLong(), anyString(), anyList(), anyInt())).thenReturn(List.of());
        when(gateway.embed(anyLong(), anyList())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
    }

    @Test
    void dropsTheWholeVectorRoundWhenEvenTheBestMatchIsTooWeak() {
        when(repository.vectorTopK(anyLong(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(chunk(1, 0.54), chunk(2, 0.51)));

        List<RetrievedChunk> result = new RetrievalService(repository, gateway, FLOOR)
                .retrieve(query("你好"));

        assertTrue(result.isEmpty(), "寒暄这类输入不该从知识库里「凑」出证据");
    }

    @Test
    void keepsTheRoundOnceTheBestMatchClearsTheFloor() {
        when(repository.vectorTopK(anyLong(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(chunk(1, 0.68), chunk(2, 0.31)));

        List<RetrievedChunk> result = new RetrievalService(repository, gateway, FLOOR)
                .retrieve(query("碳排放核算边界"));

        // 闸门看的是整轮最高分，通过之后不再逐条裁剪——否则会悄悄丢掉真实答案所在的弱相关片段
        assertEquals(2, result.size());
    }

    @Test
    void zeroDisablesTheFloor() {
        when(repository.vectorTopK(anyLong(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(chunk(1, 0.30)));

        List<RetrievedChunk> result = new RetrievalService(repository, gateway, 0)
                .retrieve(query("底噪更低的向量模型"));

        assertEquals(1, result.size());
    }

    private static RetrievalQuery query(String text) {
        return new RetrievalQuery(List.of(10L), Map.of(), List.of(text), 20, 8);
    }

    private static RetrievedChunk chunk(long id, double score) {
        return new RetrievedChunk(id, 100L + id, 10L, 11L, "doc-" + id, "node-" + id,
                "content-" + id, score);
    }
}
