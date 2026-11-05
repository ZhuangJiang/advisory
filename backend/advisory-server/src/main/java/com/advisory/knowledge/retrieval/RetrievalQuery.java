package com.advisory.knowledge.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 一次检索请求，可跨多个知识库。
 *
 * @param kbIds     待检索的知识库，必须非空（重复 id 会被折叠）
 * @param nodeScope 按知识库划分的节点范围 {@code {kbId: [nodeIds]}}：缺少该键或列表为空表示整库检索，
 *                  非空列表则限定在这些节点的子树内（ltree）；属于其他知识库的 id 与悬空 id 一律忽略
 * @param queries   查询文本；每个知识库用自己的向量模型对其向量化
 * @param perKbTopK 每个知识库、每条路径（向量/关键词）返回的条数，默认 20，越界会收敛到 [1, 100]
 * @param finalTopK RRF 融合后的结果上限，默认 8，越界会收敛到 [1, 100]
 */
public record RetrievalQuery(List<Long> kbIds,
                             Map<Long, List<Long>> nodeScope,
                             List<String> queries,
                             int perKbTopK,
                             int finalTopK) {
}
