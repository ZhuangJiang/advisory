package com.advisory.knowledge.retrieval;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hybrid retrieval orchestration (design §11 "RETRIEVE"): per knowledge base a vector path and a
 * {@code pg_trgm} keyword path, all {@code 2 × |kbIds|} ranked lists fused globally with RRF
 * (k=60), then capped at {@code finalTopK}. The SQL lives in {@link RetrievalJdbcRepository};
 * this class validates the request, drives the embedding gateway and merges multi-query results.
 *
 * <p>Binding rulings implemented here:</p>
 * <ul>
 *   <li>R2 — any requested knowledge base at {@code reindex_status = 'REINDEXING'} rejects the
 *       whole retrieval with 409 {@code KB_REINDEXING}; a {@code FAILED} knowledge base stays
 *       retrievable (only {@code REINDEXING} blocks, per design §10);</li>
 *   <li>R6 — queries are embedded per knowledge base in a single batched {@code embed()} call
 *       using that KB's {@code embedding_model_id}; a KB without an embedding model only
 *       contributes its keyword path (logged at DEBUG); an empty query list never reaches the
 *       gateway;</li>
 *   <li>R7 — {@code kbIds} must be non-empty ({@code VALIDATION_FAILED}) and is de-duplicated;
 *       {@code perKbTopK} defaults to 20 and {@code finalTopK} to 8, both clamped to [1, 100]
 *       (non-positive values mean "use the default");</li>
 *   <li>R5 — the fusion itself is delegated to {@link RrfFusion}.</li>
 * </ul>
 *
 * <p>Knowledge base ids that do not exist contribute nothing (they have no rows to search);
 * this class deliberately does not 404 for them — callers (the orchestration in Task 11) resolve
 * agent configuration against real KBs first.</p>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    static final int DEFAULT_PER_KB_TOP_K = 20;
    static final int DEFAULT_FINAL_TOP_K = 8;
    static final int MAX_TOP_K = 100;

    private final RetrievalJdbcRepository repository;
    private final EmbeddingGateway embeddingGateway;
    private final RrfFusion rrfFusion = new RrfFusion();

    public RetrievalService(RetrievalJdbcRepository repository, EmbeddingGateway embeddingGateway) {
        this.repository = repository;
        this.embeddingGateway = embeddingGateway;
    }

    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        List<Long> kbIds = validateAndDedupKbIds(query);
        int perKbTopK = normalizeTopK(query.perKbTopK(), DEFAULT_PER_KB_TOP_K);
        int finalTopK = normalizeTopK(query.finalTopK(), DEFAULT_FINAL_TOP_K);

        Map<Long, RetrievalJdbcRepository.KbMeta> metaById = repository.findKbMeta(kbIds);
        for (Long kbId : kbIds) {
            RetrievalJdbcRepository.KbMeta meta = metaById.get(kbId);
            if (meta == null) {
                log.debug("Knowledge base {} does not exist; skipping it in retrieval", kbId);
                continue;
            }
            // R2: a running reindex rejects retrieval outright; FAILED stays retrievable.
            if ("REINDEXING".equals(meta.reindexStatus())) {
                throw new AppException(ErrorCode.KB_REINDEXING, "知识库正在重建索引");
            }
        }

        List<String> queries = normalizeQueries(query.queries());
        if (queries.isEmpty()) {
            // R6: nothing to embed, nothing to match — return without touching the gateway.
            return new ArrayList<>();
        }

        Map<Long, List<Long>> nodeScope = query.nodeScope() == null ? Map.of() : query.nodeScope();

        List<List<RetrievedChunk>> rankedLists = new ArrayList<>();
        for (Long kbId : kbIds) {
            RetrievalJdbcRepository.KbMeta meta = metaById.get(kbId);
            if (meta == null) {
                continue;
            }
            List<Long> scope = effectiveScope(nodeScope.get(kbId));

            // Keyword path — one ranked list per KB, merged across the query texts.
            Map<Long, RetrievedChunk> keywordBest = new LinkedHashMap<>();
            for (String text : queries) {
                mergeBest(keywordBest, repository.keywordTopK(kbId, text, scope, perKbTopK));
            }
            addCapped(rankedLists, keywordBest, perKbTopK);

            // Vector path — skipped when the KB has no embedding model (R6).
            Long modelId = meta.embeddingModelId();
            if (modelId == null) {
                log.debug("Knowledge base {} has no embedding model; skipping the vector path", kbId);
                continue;
            }
            List<float[]> vectors = embeddingGateway.embed(modelId, queries);
            Map<Long, RetrievedChunk> vectorBest = new LinkedHashMap<>();
            for (int i = 0; i < queries.size() && i < vectors.size(); i++) {
                String vectorText = toVectorText(vectors.get(i));
                mergeBest(vectorBest, repository.vectorTopK(kbId, vectorText, scope, perKbTopK));
            }
            addCapped(rankedLists, vectorBest, perKbTopK);
        }

        return rrfFusion.fuse(rankedLists, finalTopK);
    }

    // ------------------------------------------------------------------ request validation

    private static List<Long> validateAndDedupKbIds(RetrievalQuery query) {
        if (query.kbIds() == null || query.kbIds().isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "kbIds must not be empty");
        }
        Set<Long> deduped = new LinkedHashSet<>();
        for (Long kbId : query.kbIds()) {
            if (kbId == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "kbIds must not contain null");
            }
            deduped.add(kbId);
        }
        return new ArrayList<>(deduped);
    }

    /** Defaults for non-positive values, otherwise clamped to [1, MAX_TOP_K] (R7). */
    private static int normalizeTopK(int value, int defaultValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, MAX_TOP_K);
    }

    /** Drops null/blank texts; an empty result short-circuits before any gateway call (R6). */
    private static List<String> normalizeQueries(List<String> queries) {
        if (queries == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(queries.size());
        for (String text : queries) {
            if (text != null && !text.isBlank()) {
                cleaned.add(text);
            }
        }
        return cleaned;
    }

    /** Missing key or empty list = whole KB (R4). */
    private static List<Long> effectiveScope(List<Long> scope) {
        return scope == null || scope.isEmpty() ? List.of() : scope;
    }

    // ------------------------------------------------------------------ multi-query merging

    /**
     * Keeps each chunk's best score across the query texts. Strict {@code >} keeps the first
     * occurrence on ties, so the merge is deterministic (query order and SQL {@code chunk.id}
     * tie-breaks decide).
     */
    private static void mergeBest(Map<Long, RetrievedChunk> best, List<RetrievedChunk> results) {
        for (RetrievedChunk chunk : results) {
            RetrievedChunk current = best.get(chunk.chunkId());
            if (current == null || chunk.score() > current.score()) {
                best.put(chunk.chunkId(), chunk);
            }
        }
    }

    /** Adds one knowledge base's merged path as a ranked list: score desc, lower id first. */
    private static void addCapped(List<List<RetrievedChunk>> rankedLists,
                                  Map<Long, RetrievedChunk> best, int perKbTopK) {
        if (best.isEmpty()) {
            return;
        }
        List<RetrievedChunk> ranked = new ArrayList<>(best.values());
        ranked.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            return byScore != 0 ? byScore : Long.compare(a.chunkId(), b.chunkId());
        });
        if (ranked.size() > perKbTopK) {
            ranked = new ArrayList<>(ranked.subList(0, perKbTopK));
        }
        rankedLists.add(ranked);
    }

    // ------------------------------------------------------------------ pgvector text format

    /**
     * Renders a query vector as pgvector text {@code [0.1,0.2,...]} for {@code ?::vector}
     * (ruling R8). {@code %.6f} keeps plain decimal notation (no exponent) at a precision that
     * is irrelevant for cosine ranking of {@code float4} stored vectors.
     */
    static String toVectorText(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        return sb.append(']').toString();
    }
}
