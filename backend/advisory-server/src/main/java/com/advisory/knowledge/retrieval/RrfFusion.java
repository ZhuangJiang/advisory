package com.advisory.knowledge.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面向多路检索结果的倒数排名融合（RRF，裁决 R5）。
 *
 * <p>每个输入列表就是一条有序路径——在检索场景下即一个 {@code (kbId, path, query)} 三元组：
 * 某个查询在某个知识库上的向量或关键词结果（最多 {@code 2 × |kbIds| × |queries|} 个列表）。
 * 排名从 1 开始，片段每出现在一个列表中就得 {@code 1 / (k + rank)} 分，累计分决定融合后的顺序。
 * 片段按 {@code chunkId} 去重：被多条路径（或多个知识库）命中的片段只出现一次，分数为各次之和。</p>
 *
 * <p>由于融合只看排名，不同向量模型或不同路径给出的绝对分数从不相互比较——这正是 RRF 能跨知识库使用的
 * 原因。同分时按 {@code chunkId} 升序，保证输出稳定、且与输入列表的顺序无关。</p>
 */
public class RrfFusion {

    /** RRF 的标准常数 k（裁决 R5）；60 是原论文的推荐值。 */
    public static final int DEFAULT_K = 60;

    /**
     * 把若干有序列表融合成一个排名。
     *
     * @param rankedLists 各路有序结果，最相关的在前；{@code null} 或空列表不贡献任何分数；
     *                    同一列表内重复出现的 {@code chunkId} 只按首次（最好）的排名计分
     * @param finalTopK   返回条数上限；小于 0 按 0 处理
     * @return 按融合分降序排列的片段，同分时 {@code chunkId} 较小者在前，最多 {@code finalTopK} 条
     */
    public List<RetrievedChunk> fuse(List<List<RetrievedChunk>> rankedLists, int finalTopK) {
        int cap = Math.max(finalTopK, 0);
        if (rankedLists == null || rankedLists.isEmpty() || cap == 0) {
            return new ArrayList<>();
        }

        Map<Long, RetrievedChunk> firstSeen = new LinkedHashMap<>();
        Map<Long, Double> fusedScores = new LinkedHashMap<>();

        for (List<RetrievedChunk> ranked : rankedLists) {
            if (ranked == null) {
                continue;
            }
            Set<Long> seenInList = new HashSet<>();
            int rank = 1;
            for (RetrievedChunk chunk : ranked) {
                if (chunk == null) {
                    rank++;
                    continue;
                }
                // 单个列表内重复的行只保留其最好（首次）的排名，避免同一路结果被重复加分。
                if (seenInList.add(chunk.chunkId())) {
                    fusedScores.merge(chunk.chunkId(), 1.0 / (DEFAULT_K + rank), Double::sum);
                    firstSeen.putIfAbsent(chunk.chunkId(), chunk);
                }
                rank++;
            }
        }

        List<RetrievedChunk> fused = new ArrayList<>(fusedScores.size());
        for (Map.Entry<Long, Double> entry : fusedScores.entrySet()) {
            RetrievedChunk chunk = firstSeen.get(entry.getKey());
            fused.add(new RetrievedChunk(chunk.chunkId(), chunk.documentId(), chunk.kbId(),
                    chunk.nodeId(), chunk.docName(), chunk.nodeName(), chunk.content(),
                    entry.getValue()));
        }
        fused.sort(Comparator.comparingDouble((RetrievedChunk c) -> c.score()).reversed()
                .thenComparing(RetrievedChunk::chunkId));

        if (fused.size() > cap) {
            return new ArrayList<>(fused.subList(0, cap));
        }
        return fused;
    }
}
