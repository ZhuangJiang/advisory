package com.advisory.knowledge.service;

import com.advisory.common.api.PageResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.api.dto.CreateKnowledgeBaseRequest;
import com.advisory.knowledge.api.dto.KnowledgeBaseResponse;
import com.advisory.knowledge.api.dto.UpdateKnowledgeBaseRequest;
import com.advisory.knowledge.domain.KnowledgeBase;
import com.advisory.knowledge.repo.KnowledgeBaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/**
 * 知识库的创建、列表、更新与重建索引。
 *
 * <p>列表用 {@code JdbcTemplate} 实现，每行的 {@code documentCount} 由 {@code LEFT JOIN LATERAL} 在一条 SQL 里
 * 算出（{@code documents} 表目前没有 JPA 实体）。删除时会拒绝两类知识库：仍被 {@code agents} 行引用的，
 * 以及正在重建索引的。</p>
 *
 * <p>{@link #reindex(Long)} 按裁决 R6 执行：先用 CAS 把 {@code reindex_status} 置为 {@code REINDEXING}，
 * 再把后台任务交给 worker 专用的单线程执行器；任务把所有非 {@code DELETED} 文档重置为 {@code PENDING}
 * 并同步跑完入库流水线，最后在 {@code finally} 里把状态收敛为 {@code READY}/{@code FAILED}，
 * 保证知识库绝不会永远卡在 {@code REINDEXING}。任务计入 worker 的公共空闲计数器，测试因此可以
 * {@code awaitIdle} 等整个重建结束。</p>
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 列表与单行读取共用的 {@code SELECT}，确保两处的文档计数（必须排除软删除行）算法完全一致。
     */
    private static final String BASE_SQL = """
            SELECT kb.id,
                   kb.name,
                   kb.description,
                   kb.embedding_model_id,
                   kb.chunk_config::text AS chunk_config,
                   kb.reindex_status,
                   kb.created_at,
                   COALESCE(d.doc_count, 0) AS document_count
            FROM knowledge_bases kb
            LEFT JOIN LATERAL (
                SELECT count(*) AS doc_count FROM documents doc
                WHERE doc.kb_id = kb.id AND doc.status <> 'DELETED'
            ) d ON TRUE
            """;

    private static final String LIST_SQL = BASE_SQL + " ORDER BY kb.id LIMIT ? OFFSET ?";

    private static final String DETAIL_SQL = BASE_SQL + " WHERE kb.id = ?";

    private static final RowMapper<KnowledgeBaseResponse> ROW_MAPPER =
            (rs, rowNum) -> new KnowledgeBaseResponse(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    (Long) rs.getObject("embedding_model_id"),
                    rs.getString("chunk_config"),
                    rs.getString("reindex_status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getLong("document_count"));

    private final KnowledgeBaseRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DocumentIngestWorker worker;
    private final StorageService storage;

    public KnowledgeBaseService(KnowledgeBaseRepository repository,
                                JdbcTemplate jdbc,
                                ObjectMapper objectMapper,
                                DocumentIngestWorker worker,
                                StorageService storage) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.worker = worker;
        this.storage = storage;
    }

    /**
     * 启动时为每个既有知识库补齐专属存储桶。
     *
     * <p>「一库一桶」上线后，存量知识库的桶不会自己出现；少了这一步，老库要等到第一次上传才报
     * {@code NoSuchBucket}。MinIO 暂不可达时只记警告——与启动期的既有约定一致，不阻断应用启动。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBuckets() {
        for (KnowledgeBase kb : repository.findAll()) {
            try {
                storage.createBucket(kb.getId());
            } catch (RuntimeException e) {
                log.warn("Could not ensure bucket of knowledge base {}: {}", kb.getId(), e.toString());
            }
        }
    }

    /** 新建知识库。 */
    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest body, Long createdBy) {
        String name = validateName(body.name(), MAX_NAME_LENGTH);
        KnowledgeBase kb = new KnowledgeBase(name);
        kb.setDescription(normalizeDescription(body.description()));
        kb.setEmbeddingModelId(body.embeddingModelId());
        kb.setChunkConfig(normalizeChunkConfig(body.chunkConfig()));
        kb.setCreatedBy(createdBy);
        kb = repository.save(kb);
        // 建桶失败会让本次建库回滚：存储没准备好时，让用户拿到明确的 500，而不是等到上传才发现。
        // 万一是 MinIO 侧建好了而事务回滚，留下的只是一个空桶，代价可以接受。
        storage.createBucket(kb.getId());
        return KnowledgeBaseResponse.from(kb);
    }

    /** 局部更新；重建期间禁止改向量模型与切片配置。 */
    @Transactional
    public KnowledgeBaseResponse update(Long id, UpdateKnowledgeBaseRequest body) {
        KnowledgeBase kb = find(id);
        // 与删除同样的防护：重建过程中改动 embedding_model_id 或 chunk_config，
        // 会让正在运行的重建读到一半新一半旧的配置，最终产出两套参数切出来的片段。
        if ("REINDEXING".equals(kb.getReindexStatus())) {
            throw new AppException(ErrorCode.KB_REINDEXING, "知识库正在重建索引");
        }
        if (body.name() != null) {
            kb.setName(validateName(body.name(), MAX_NAME_LENGTH));
        }
        if (body.description() != null) {
            kb.setDescription(normalizeDescription(body.description()));
        }
        if (body.embeddingModelId() != null) {
            kb.setEmbeddingModelId(body.embeddingModelId());
        }
        if (body.chunkConfig() != null) {
            kb.setChunkConfig(normalizeChunkConfig(body.chunkConfig()));
        }
        kb = repository.save(kb);
        return KnowledgeBaseResponse.from(kb);
    }

    /** 删除知识库；被引用或正在重建时拒绝。 */
    @Transactional
    public void delete(Long id) {
        KnowledgeBase kb = find(id);
        if ("REINDEXING".equals(kb.getReindexStatus())) {
            throw new AppException(ErrorCode.KB_REINDEXING, "知识库正在重建索引");
        }
        // kb_ids 里的 id 可能存成 JSON 数字，也可能存成字符串（"5"）；因此把文本元素按 bigint 比较，
        // 而不是依赖 jsonb 包含运算对某一种表示形式的假设。
        Long references = jdbc.queryForObject("""
                SELECT count(*)
                FROM agents a
                CROSS JOIN LATERAL jsonb_array_elements_text(a.kb_ids) AS e(elem)
                WHERE a.deleted_at IS NULL
                  AND e.elem ~ '^-?[0-9]+$' AND e.elem::bigint = ?
                """, Long.class, id);
        if (references != null && references > 0) {
            throw new AppException(ErrorCode.KB_IN_USE, "知识库已被某个 Agent 引用");
        }
        long kbId = kb.getId();
        repository.delete(kb);
        // 桶的清理放在提交之后：若事务回滚，桶必须还在，否则数据行还在、文件却已经没了。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.deleteBucketQuietly(kbId);
            }
        });
    }

    /** 分页列出知识库并算出现有文档数。 */
    @Transactional(readOnly = true)
    public PageResponse<KnowledgeBaseResponse> list(int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        Long total = jdbc.queryForObject("SELECT count(*) FROM knowledge_bases", Long.class);
        long totalCount = total == null ? 0 : total;

        List<KnowledgeBaseResponse> items = jdbc.query(LIST_SQL, ROW_MAPPER,
                pageSize, pageIndex * pageSize);

        return PageResponse.of(items, pageIndex, pageSize, totalCount);
    }

    /**
     * 读取单个知识库及其实时文档数，供详情页头部展示。
     *
     * <p>id 不存在时返回 404 而非空对象，与其它读接口保持一致。</p>
     *
     * @param id 知识库主键
     * @return 知识库详情
     */
    @Transactional(readOnly = true)
    public KnowledgeBaseResponse get(Long id) {
        return jdbc.query(DETAIL_SQL, ROW_MAPPER, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在"));
    }

    private KnowledgeBase find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在"));
    }

    /**
     * 启动一次全量重建（裁决 R6），步骤顺序不可调换：
     * <ol>
     *   <li>知识库不存在返回 404；</li>
     *   <li>CAS 把 {@code reindex_status} 置为 {@code 'REINDEXING'}，影响 0 行说明已在重建，返回 409；</li>
     *   <li>没有向量模型的知识库恢复为原状态并返回 400，否则它会卡在 REINDEXING 且无内容可向量化；</li>
     *   <li>把后台重建交给专用执行器（计入入库 worker 的空闲计数器）；交接被拒则把状态恢复为
     *       {@code FAILED}。</li>
     * </ol>
     * 接口立即返回 202，实际工作由 {@link #runReindex(Long)} 完成。
     *
     * @param id 知识库主键
     */
    public void reindex(Long kbId) {
        find(kbId); // id 不存在时由这里抛 404 RESOURCE_NOT_FOUND
        String previousStatus = jdbc.queryForObject(
                "SELECT reindex_status FROM knowledge_bases WHERE id = ?", String.class, kbId);
        int updated = jdbc.update("""
                UPDATE knowledge_bases SET reindex_status = 'REINDEXING'
                WHERE id = ? AND reindex_status <> 'REINDEXING'
                """, kbId);
        if (updated == 0) {
            throw new AppException(ErrorCode.KB_REINDEXING, "知识库正在重建索引");
        }
        Long embeddingModelId = jdbc.queryForObject(
                "SELECT embedding_model_id FROM knowledge_bases WHERE id = ?", Long.class, kbId);
        if (embeddingModelId == null) {
            // 恢复被 CAS 覆盖掉的原状态（不能硬编码成 READY——一个本就 FAILED 且没有向量模型的知识库
            // 必须保持 FAILED，裁决 R6 第 3 步）。
            jdbc.update("""
                    UPDATE knowledge_bases SET reindex_status = ?
                    WHERE id = ? AND reindex_status = 'REINDEXING'
                    """, previousStatus, kbId);
            throw new AppException(ErrorCode.VALIDATION_FAILED, "知识库未配置 embedding 模型");
        }
        boolean handedOff = worker.submitReindexTask(() -> runReindex(kbId));
        if (!handedOff) {
            // 绝不能让知识库卡死在 REINDEXING（裁决 R6）。
            jdbc.update("""
                    UPDATE knowledge_bases SET reindex_status = 'FAILED'
                    WHERE id = ? AND reindex_status = 'REINDEXING'
                    """, kbId);
            log.error("Reindex task for knowledge base {} could not be handed off", kbId);
        }
    }

    /**
     * 重建的实际执行体，运行在专用重建线程上：把该知识库所有非 DELETED 文档重置为 PENDING 并清空
     * {@code error_msg}，逐个同步跑完同一条入库流水线，最后收敛 {@code reindex_status}——没有文档失败则
     * READY，否则 FAILED；零文档同样收敛为 READY。
     *
     * @param kbId 知识库主键
     */
    private void runReindex(Long kbId) {
        try {
            jdbc.update("""
                    UPDATE documents SET status = 'PENDING', error_msg = NULL, updated_at = now()
                    WHERE kb_id = ? AND status <> 'DELETED'
                    """, kbId);
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM documents WHERE kb_id = ? AND status <> 'DELETED' ORDER BY id",
                    Long.class, kbId);
            for (Long id : ids) {
                worker.runPipeline(id);
            }
        } catch (RuntimeException e) {
            // 流水线是逐文档失败的；无论这个线程上冒出什么异常，都不能阻止下面 finally 里的状态收尾。
            log.error("Reindex of knowledge base {} failed", kbId, e);
        } finally {
            settleReindexStatus(kbId);
        }
    }

    /** 依据是否有文档落到 FAILED，把 REINDEXING 以 CAS 方式收敛为 READY 或 FAILED。 */
    private void settleReindexStatus(Long kbId) {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE kb_id = ? AND status = 'FAILED'",
                Integer.class, kbId);
        String target = (failed != null && failed > 0) ? "FAILED" : "READY";
        jdbc.update("""
                UPDATE knowledge_bases SET reindex_status = ?
                WHERE id = ? AND reindex_status = 'REINDEXING'
                """, target, kbId);
        log.info("Reindex of knowledge base {} settled to {}", kbId, target);
    }

    private static String validateName(String raw, int maxLength) {
        if (raw == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "名称不能为空");
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "名称不能为空");
        }
        if (name.length() > maxLength) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "名称长度不能超过 " + maxLength + " 个字符");
        }
        rejectControlCharacters(name);
        return name;
    }

    private static void rejectControlCharacters(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "名称不能包含控制字符");
            }
        }
    }

    private static String normalizeDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeChunkConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        try {
            objectMapper.readTree(trimmed);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "分块配置必须是合法的 JSON");
        }
        return trimmed;
    }
}
