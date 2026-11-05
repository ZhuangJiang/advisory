package com.advisory.knowledge.retrieval;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索编排（设计 §11 的 RETRIEVE）：每个知识库跑一条向量路径和一条 {@code pg_trgm} 关键词路径，
 * 且是<em>按每条查询文本</em>分别跑——每个有序列表对应一个 {@code (kbId, path, query)} 三元组，
 * 最多 {@code 2 × |kbIds| × |queries|} 个列表——最后统一用 RRF（k=60）融合，并截断到 {@code finalTopK}。
 * 之所以保持列表彼此独立，正是为了让「被多条查询命中的片段」能在 {@link RrfFusion#fuse} 中按列表各累加
 * 一次 {@code 1/(k+rank)}（去重与求和都在那里按 {@code chunkId} 完成）。SQL 在
 * {@link RetrievalJdbcRepository}，本类负责校验请求并驱动向量网关。
 *
 * <p>在此落实的裁决：</p>
 * <ul>
 *   <li>R2——只要有一个请求的知识库处于 {@code reindex_status = 'REINDEXING'}，整次检索就以 409
 *       {@code KB_REINDEXING} 拒绝；{@code FAILED} 状态的知识库仍可检索（按设计 §10，只有重建中才拦）；</li>
 *   <li>R6——查询按知识库分组，用该库的 {@code embedding_model_id} 一次性批量 {@code embed()}；
 *       没有配置向量模型的知识库只贡献关键词路径（打 DEBUG 日志）；查询列表为空时根本不会调用网关；</li>
 *   <li>R7——{@code kbIds} 必须非空（否则 {@code VALIDATION_FAILED}）并去重；{@code perKbTopK} 默认 20、
 *       {@code finalTopK} 默认 8，均收敛到 [1, 100]（非正数表示「用默认值」）；</li>
 *   <li>R5——融合本身委托给 {@link RrfFusion}。</li>
 *   <li>相关性下限（{@code app.retrieval.min-similarity}，默认 0.55）——向量路径的整轮最高分低于它时，
 *       这一路结果整体丢弃。没有这道闸门，任何问题（包括「你好」）都能凑出 {@code topK} 条「最像的」
 *       片段，编排器就会把噪声当证据。</li>
 * </ul>
 *
 * <p>不存在的知识库 id 不会贡献任何结果（它没有任何行可查）；本类刻意不对其返回 404——调用方
 * （Task 11 的编排）在检索之前就已按真实知识库解析过 Agent 配置。</p>
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

    /**
     * 向量路径的相关性下限。低于它的整轮结果按「没找到」处理。
     *
     * <p>向量检索没有下限时一定会返回 {@code topK} 条「最像的」——哪怕问题与知识库毫无关系，
     * 余弦相似度也总会给出 0.4–0.55 的分数（bge-m3 的底噪）。这些片段一旦进入 RRF，就会让编排器
     * 以为「检索到了证据」，进而被判证据不足、补一轮、最后基于噪声作答。</p>
     *
     * <p>判据取<b>整轮最高分</b>而不是逐条过滤：只要有片段真的相关（实测 ≥ 0.60），就保留整轮结果，
     * 召回不受影响；只有整轮都没过线时才判为「知识库没有这方面内容」。</p>
     */
    private final double minSimilarity;

    public RetrievalService(RetrievalJdbcRepository repository, EmbeddingGateway embeddingGateway,
                            @Value("${app.retrieval.min-similarity:0.55}") double minSimilarity) {
        this.repository = repository;
        this.embeddingGateway = embeddingGateway;
        this.minSimilarity = minSimilarity;
    }

    /** 按知识库分别跑向量与关键词路径，再用 RRF 全局融合。 */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        List<Long> kbIds = validateAndDedupKbIds(query);
        int perKbTopK = normalizeTopK(query.perKbTopK(), DEFAULT_PER_KB_TOP_K);
        int finalTopK = normalizeTopK(query.finalTopK(), DEFAULT_FINAL_TOP_K);

        Map<Long, RetrievalJdbcRepository.KbMeta> metaById = repository.findKbMeta(kbIds);
        for (Long kbId : kbIds) {
            RetrievalJdbcRepository.KbMeta meta = metaById.get(kbId);
            if (meta == null) {
                // 这里静默跳过是有意的：Agent 的知识库配置在保存时就已校验过（Task 10），
                // 检索不该因为一个过期的 id 变成 500——它什么都不贡献即可。
                log.debug("Knowledge base {} does not exist; skipping it in retrieval", kbId);
                continue;
            }
            // R2：重建中直接拒绝检索；FAILED 状态仍然可检索。
            if ("REINDEXING".equals(meta.reindexStatus())) {
                throw new AppException(ErrorCode.KB_REINDEXING, "知识库正在重建索引");
            }
        }

        List<String> queries = normalizeQueries(query.queries());
        if (queries.isEmpty()) {
            // R6：没有要向量化的内容，也就没有可匹配的东西，直接返回，不碰网关。
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

            // 每个 (知识库, 查询) 一个关键词列表，绝不跨查询合并——
            // 只有保持独立，命中多条查询的片段才能在 RrfFusion.fuse 中按列表逐个累加 1/(k+rank)。
            for (String text : queries) {
                List<RetrievedChunk> keywordList =
                        repository.keywordTopK(kbId, text, scope, perKbTopK);
                if (!keywordList.isEmpty()) {
                    rankedLists.add(keywordList);
                }
            }

            // 向量路径——知识库未配置向量模型时跳过（R6）。
            Long modelId = meta.embeddingModelId();
            if (modelId == null) {
                log.debug("Knowledge base {} has no embedding model; skipping the vector path", kbId);
                continue;
            }
            List<float[]> vectors = embeddingGateway.embed(modelId, queries);
            for (int i = 0; i < queries.size() && i < vectors.size(); i++) {
                // 每个 (知识库, 查询) 一个向量列表，与关键词路径同样不合并。
                List<RetrievedChunk> vectorList = repository.vectorTopK(
                        kbId, toVectorText(vectors.get(i)), scope, perKbTopK);
                if (!vectorList.isEmpty() && passesRelevanceFloor(vectorList)) {
                    rankedLists.add(vectorList);
                }
            }
        }

        return rrfFusion.fuse(rankedLists, finalTopK);
    }

    // ------------------------------------------------------------------ 相关性下限

    /**
     * 整轮向量结果是否够相关：只看首条（分数最高）即可，SQL 已按距离升序排好。
     *
     * @param vectorList 已按相似度降序排列的向量结果，非空
     * @return 首条得分达到 {@link #minSimilarity} 时为 {@code true}；阈值为 0 表示不设限
     */
    private boolean passesRelevanceFloor(List<RetrievedChunk> vectorList) {
        if (minSimilarity <= 0) {
            return true;
        }
        double best = vectorList.get(0).score();
        if (best >= minSimilarity) {
            return true;
        }
        log.debug("Dropping a vector list: best similarity {} is below the {} floor", best, minSimilarity);
        return false;
    }

    // ------------------------------------------------------------------ 请求校验

    private static List<Long> validateAndDedupKbIds(RetrievalQuery query) {
        if (query.kbIds() == null || query.kbIds().isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "知识库 id 列表不能为空");
        }
        Set<Long> deduped = new LinkedHashSet<>();
        for (Long kbId : query.kbIds()) {
            if (kbId == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "知识库 id 列表不能包含 null");
            }
            deduped.add(kbId);
        }
        return new ArrayList<>(deduped);
    }

    /** 非正数取默认值，其余收敛到 [1, MAX_TOP_K]（裁决 R7）。 */
    private static int normalizeTopK(int value, int defaultValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, MAX_TOP_K);
    }

    /** 丢弃空文本；结果为空时在调用网关之前就短路返回（R6）。 */
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

    /** 缺少该键或列表为空表示整库检索（R4）。 */
    private static List<Long> effectiveScope(List<Long> scope) {
        return scope == null || scope.isEmpty() ? List.of() : scope;
    }

    // ------------------------------------------------------------------ pgvector 文本格式

    /**
     * 把查询向量渲染成 pgvector 文本 {@code [0.1,0.2,...]}，供 {@code ?::vector} 使用（裁决 R8）。
     *
     * <p>{@code %.6f} 保证输出始终是普通小数而非指数形式；这个精度对 {@code float4} 存储向量的余弦排序
     * 没有实质影响，但避免了科学计数法导致解析失败。</p>
     *
     * @param vector 向量分量
     * @return pgvector 文本形式
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
