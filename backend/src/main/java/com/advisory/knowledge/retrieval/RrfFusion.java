package com.advisory.knowledge.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion (RRF) over ranked retrieval paths (ruling R5).
 *
 * <p>Every input list is one ranked path — for retrieval that is the vector path and the keyword
 * path of each requested knowledge base ({@code 2 × |kbIds|} lists). Rank is 1-based; each chunk
 * gains {@code 1 / (k + rank)} in every list it appears in, and the accumulated score decides the
 * fused order. Chunks are deduplicated by {@code chunkId}: a chunk found by several paths (or
 * several knowledge bases, if ids ever collided) appears exactly once with the summed score.</p>
 *
 * <p>Because fusion only ever looks at ranks, scores from different embedding models or paths are
 * never compared — that is the whole point of RRF for cross-KB retrieval. Ties break by lower
 * {@code chunkId} so the output is stable across calls and independent of input list order.</p>
 */
public class RrfFusion {

    /** Standard RRF constant (ruling R5). */
    public static final int DEFAULT_K = 60;

    /**
     * Fuses the given ranked lists into one ranking.
     *
     * @param rankedLists ranked paths, best chunk first; {@code null} lists and empty lists simply
     *                     contribute nothing; duplicates of the same {@code chunkId} within one
     *                     list only count for their first (best) rank
     * @param finalTopK   maximum number of returned chunks; values below 0 are treated as 0
     * @return chunks ordered by fused score descending, ties by lower chunkId, capped at
     *         {@code finalTopK}
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
                // A duplicate row inside a single list keeps its best (first) rank only.
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
