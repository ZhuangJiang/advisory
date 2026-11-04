package com.advisory.knowledge.retrieval;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hybrid retrieval orchestration (design §11 "RETRIEVE"): per knowledge base a vector path and a
 * {@code pg_trgm} keyword path <em>per query text</em> — every ranked list is one
 * {@code (kbId, path, query)} triple, so at most {@code 2 × |kbIds| × |queries|} lists — all
 * fused globally with RRF (k=60) and capped at {@code finalTopK}. Keeping the lists separate is
 * what lets a chunk that several queries found accumulate one {@code 1/(k+rank)} contribution
 * per list inside {@link RrfFusion#fuse} (dedup and summation happen there, by {@code chunkId}).
 * The SQL lives in {@link RetrievalJdbcRepository}; this class validates the request and drives
 * the embedding gateway.
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
                // Silent skip is deliberate: agent kb config is validated when the Agent is saved
                // (Task 10), and retrieval must not turn a stale id into a 500 — it just
                // contributes nothing.
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

            // One keyword list per (KB, query): never merged across queries — a chunk ranked in
            // several query lists must accumulate 1/(k+rank) per list inside RrfFusion.fuse.
            for (String text : queries) {
                List<RetrievedChunk> keywordList =
                        repository.keywordTopK(kbId, text, scope, perKbTopK);
                if (!keywordList.isEmpty()) {
                    rankedLists.add(keywordList);
                }
            }

            // Vector path — skipped when the KB has no embedding model (R6).
            Long modelId = meta.embeddingModelId();
            if (modelId == null) {
                log.debug("Knowledge base {} has no embedding model; skipping the vector path", kbId);
                continue;
            }
            List<float[]> vectors = embeddingGateway.embed(modelId, queries);
            for (int i = 0; i < queries.size() && i < vectors.size(); i++) {
                // One vector list per (KB, query), same no-merge rule as the keyword path.
                List<RetrievedChunk> vectorList = repository.vectorTopK(
                        kbId, toVectorText(vectors.get(i)), scope, perKbTopK);
                if (!vectorList.isEmpty()) {
                    rankedLists.add(vectorList);
                }
            }
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
