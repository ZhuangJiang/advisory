package com.advisory.knowledge.retrieval;

import java.util.List;
import java.util.Map;

/**
 * One retrieval request over one or more knowledge bases.
 *
 * @param kbIds      knowledge bases to search; must be non-empty (repeated ids are collapsed)
 * @param nodeScope  per-KB node scope {@code {kbId: [nodeIds]}}; a missing key or an empty list
 *                   means the whole knowledge base, a non-empty list restricts to the subtrees of
 *                   those nodes (ltree), ids belonging to other knowledge bases or dangling ids
 *                   are ignored
 * @param queries    query texts; each knowledge base embeds them with its own embedding model
 * @param perKbTopK  per path (vector / keyword) result size per knowledge base, default 20, clamped to [1, 100]
 * @param finalTopK  RRF-fused result cap, default 8, clamped to [1, 100]
 */
public record RetrievalQuery(List<Long> kbIds,
                             Map<Long, List<Long>> nodeScope,
                             List<String> queries,
                             int perKbTopK,
                             int finalTopK) {
}
