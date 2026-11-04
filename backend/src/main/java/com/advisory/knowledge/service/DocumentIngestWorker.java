package com.advisory.knowledge.service;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.domain.DocumentStatus;
import com.advisory.knowledge.ingest.ChunkConfig;
import com.advisory.knowledge.ingest.DocumentParser;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import com.advisory.knowledge.ingest.ParsedDocument;
import com.advisory.knowledge.ingest.StructureAwareChunker;
import com.advisory.knowledge.ingest.TextChunk;
import com.advisory.knowledge.ingest.ChunkWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 异步文档入库队列与文档状态机。
 *
 * <p>所有状态写入都是「比较并设置」：{@code UPDATE documents SET status = ? WHERE id = ? AND status = ?}。
 * 影响行数为 0 说明文档已被别的流程推进，此时任务只记日志并放弃，绝不覆盖更新的状态。
 * 流水线为 {@code PENDING -> PARSING -> CHUNKING -> EMBEDDING -> READY}：解析（解析结果存到 MinIO 的
 * {@code parsed_key}）、按知识库的 {@code chunk_config} 做结构化切片、逐片段向量化，最后幂等地
 * {@code replaceAll} 片段。切片结果为空时文档仍以 {@code READY} 结束（零个片段）；其他任何失败都转为
 * {@code FAILED}，并把 {@code error_msg} 截断后保存。</p>
 *
 * <p>重启恢复由一个启动期的 {@link ApplicationRunner} 完成：后端是单副本，任何人留在中间状态的行使
 * 都属于一个已经死掉的进程。这些行会被重置为 {@code PENDING} 并重新入队，终态行则原样不动。被恢复的文档
 * 会重新走一遍 {@code PARSING} 的 CAS：每个阶段都是幂等的（解析产物被覆盖写、片段集合用 {@code replaceAll}），
 * 因此从头重放永远安全。</p>
 *
 * <p>{@link #awaitIdle(Duration)} 用「计数器 + 条件变量」跟踪已提交的任务，测试因此可以等真实完成而不用睡眠。
 * 所有让计数加一的路径都在 {@code finally} 里减一，包括提交被拒绝的情况，计数器不会泄漏。
 * 知识库重建任务也挂在同一个计数器上（跑在专用的单线程执行器里，见 {@link #submitReindexTask(Runnable)}），
 * 所以重建可以和普通入库一样被等待。</p>
 */
@Component
public class DocumentIngestWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestWorker.class);

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final String INTERMEDIATE_STATUSES = "('PARSING', 'CHUNKING', 'EMBEDDING')";

    private final JdbcTemplate jdbc;
    private final StorageService storage;
    private final DocumentParser parser;
    private final StructureAwareChunker chunker;
    private final ChunkWriter chunkWriter;
    private final EmbeddingGateway gateway;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final ThreadPoolTaskExecutor reindexExecutor = new ThreadPoolTaskExecutor();

    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleCondition = idleLock.newCondition();
    private int pendingTasks;

    public DocumentIngestWorker(JdbcTemplate jdbc,
                                StorageService storage,
                                DocumentParser parser,
                                StructureAwareChunker chunker,
                                ChunkWriter chunkWriter,
                                EmbeddingGateway gateway,
                                ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.chunkWriter = chunkWriter;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("doc-ingest-");
        executor.initialize();
        // 重建用单线程串行执行整库重建，排队本身即设计意图（裁决 R6）。
        reindexExecutor.setCorePoolSize(1);
        reindexExecutor.setMaxPoolSize(1);
        reindexExecutor.setQueueCapacity(100);
        reindexExecutor.setThreadNamePrefix("kb-reindex-");
        reindexExecutor.initialize();
    }

    /** 启动钩子：把上一个进程留在流水线中途的文档重新入队。 */
    @Override
    public void run(ApplicationArguments args) {
        int requeued = recoverInterrupted().size();
        if (requeued > 0) {
            log.info("Re-queued {} document(s) left in an intermediate state by the previous run", requeued);
        }
        List<Long> settled = recoverInterruptedReindexes();
        if (!settled.isEmpty()) {
            log.info("Settled knowledge base(s) left REINDEXING by the previous run: {}", settled);
        }
    }

    /**
     * 把卡在 {@code PARSING}/{@code CHUNKING}/{@code EMBEDDING} 的行重置为 {@code PENDING} 并重新入队。
     *
     * <p>用一条 {@code UPDATE ... RETURNING id} 而不是「先查再改」：若分成两条语句，工作线程完全可能在
     * 两者之间把文档从 {@code PENDING} 推进到 {@code PARSING}，随后它被重置为 {@code PENDING} 却没出现在
     * 返回的 id 列表里，接着自己的比较并设置失败——文档就此卡死，且没有任何接口能救回来。
     * 本方法可重复调用：第二次只会看到仍处于中间状态的行（第一次提交的任务可能已把它们往前推进，
     * 那就再重置、再入队一次）。</p>
     *
     * @return 被重新入队的 id，顺序与 PostgreSQL 返回的顺序一致
     */
    public List<Long> recoverInterrupted() {
        List<Long> ids = jdbc.queryForList(
                "UPDATE documents SET status = 'PENDING', updated_at = now() "
                        + "WHERE status IN " + INTERMEDIATE_STATUSES + " RETURNING id",
                Long.class);
        if (ids.isEmpty()) {
            return List.of();
        }
        log.info("Reset {} interrupted document(s) to PENDING", ids.size());
        ids.forEach(this::enqueue);
        return ids;
    }

    /**
     * 收尾上一个进程遗留在 {@code REINDEXING} 的知识库。
     *
     * <p>崩溃发生在状态 CAS 与后台收尾之间，此后没有任何东西会替它收尾，于是所有写接口都会永远返回 409。
     * 这里同样用一条 {@code UPDATE ... RETURNING id}（理由见 {@link #recoverInterrupted()}）：
     * 这些行被置为 {@code FAILED}（设计规定「失败置 FAILED」，由管理员重新触发重建），并记录 id。
     * 重建本身刻意<em>不</em>自动重启：崩溃原因未知，自动重试只会把问题藏起来。方法幂等——第二次调用
     * 找不到 {@code REINDEXING} 行，返回空列表。</p>
     *
     * <p>由 {@link #run(ApplicationArguments)} 在启动阶段调用，与 {@link #recoverInterrupted()} 同一时机。</p>
     *
     * @return 被收尾的知识库 id
     */
    public List<Long> recoverInterruptedReindexes() {
        List<Long> ids = jdbc.queryForList(
                "UPDATE knowledge_bases SET reindex_status = 'FAILED' "
                        + "WHERE reindex_status = 'REINDEXING' RETURNING id",
                Long.class);
        if (ids.isEmpty()) {
            return List.of();
        }
        log.info("Settled {} knowledge base(s) stuck in REINDEXING to FAILED: {}", ids.size(), ids);
        return ids;
    }

    /** 提交一个文档入库任务。同一个 id 允许重复入队，最终由状态 CAS 决定谁真正执行。 */
    public void enqueue(Long documentId) {
        if (documentId == null) {
            return;
        }
        submit(() -> runPipeline(documentId), "ingest document " + documentId);
    }

    /**
     * 软删除后的异步清理：删掉片段、原始对象与解析产物（仅当 {@code parsed_key} 已写入时）。
     *
     * <p>在任务真正执行时会重新校验一次状态，删片段的语句内部又再校验一次，因此一个已不再是
     * {@code DELETED} 的文档，绝不会被这次清理误删掉正在入库的片段。</p>
     *
     * @param documentId 文档主键
     * @param objectKey  原始对象键
     * @param parsedKey  解析产物键，可为 {@code null}
     */
    public void enqueueCleanup(Long documentId) {
        if (documentId == null) {
            return;
        }
        submit(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status, object_key, parsed_key FROM documents WHERE id = ?", documentId);
            if (rows.isEmpty()) {
                log.info("Document {} is gone; nothing to clean up", documentId);
                return;
            }
            Map<String, Object> row = rows.get(0);
            if (!DocumentStatus.DELETED.name().equals(row.get("status"))) {
                log.info("Document {} is {}; skipping storage cleanup", documentId, row.get("status"));
                return;
            }
            jdbc.update("""
                    DELETE FROM chunks WHERE document_id = ?
                      AND EXISTS (SELECT 1 FROM documents d WHERE d.id = ? AND d.status = 'DELETED')
                    """, documentId, documentId);
            removeQuietly(documentId, (String) row.get("object_key"));
            removeQuietly(documentId, (String) row.get("parsed_key"));
        }, "clean up document " + documentId);
    }

    /**
     * 把知识库重建任务提交到专用的单线程执行器，并计入同一个 {@link #awaitIdle(Duration)} 计数器，
     * 测试因此能等到真实完成。任务自身必须在 {@code finally} 里收尾知识库状态。
     *
     * @param task 重建任务
     * @return 成功交给执行器返回 {@code true}；被拒绝时返回 {@code false}，此时调用方必须自行恢复知识库状态
     *         （裁决 R6）
     */
    public boolean submitReindexTask(Runnable task) {
        taskSubmitted();
        boolean handedOff = false;
        try {
            reindexExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // 任务本身会收尾知识库状态，这里是最后一道兜底。
                    log.error("Reindex task failed unexpectedly", t);
                } finally {
                    taskCompleted();
                }
            });
            handedOff = true;
        } catch (RuntimeException e) {
            log.error("Could not hand off reindex task to the reindex executor", e);
        } finally {
            if (!handedOff) {
                taskCompleted();
            }
        }
        return handedOff;
    }

    /**
     * 状态迁移的比较并设置。
     *
     * @param documentId 文档主键
     * @param from       期望的当前状态
     * @param to         目标状态
     * @param errorMsg   错误信息，{@code null} 表示清空该列
     * @return 行已不在 {@code from} 状态（被别的流程推进了）时返回 {@code false}，调用方据此放弃本次任务，
     *         而不是当成错误
     */
    public boolean transition(long documentId, DocumentStatus from, DocumentStatus to, String errorMsg) {
        int updated = jdbc.update("""
                UPDATE documents SET status = ?, error_msg = ?, updated_at = now()
                WHERE id = ? AND status = ?
                """, to.name(), truncate(errorMsg), documentId, from.name());
        if (updated == 0) {
            log.info("Document {} is no longer {}; dropping the {} transition", documentId, from, to);
            return false;
        }
        return true;
    }

    /** 把文档置为 {@code FAILED}，已是终态则不做改动。 */
    public void fail(long documentId, String errorMsg) {
        int updated = jdbc.update("""
                UPDATE documents SET status = 'FAILED', error_msg = ?, updated_at = now()
                WHERE id = ? AND status NOT IN ('READY', 'FAILED', 'DELETED')
                """, truncate(errorMsg), documentId);
        if (updated == 0) {
            log.info("Document {} is already terminal; not marking it FAILED", documentId);
        }
    }

    /**
     * 等待所有已提交任务结束。
     *
     * @param timeout 最长等待时间
     * @return 全部完成返回 {@code true}；超时或被中断返回 {@code false}
     */
    public boolean awaitIdle(Duration timeout) {
        long remainingNanos = timeout.toNanos();
        idleLock.lock();
        try {
            while (pendingTasks > 0) {
                if (remainingNanos <= 0) {
                    return false;
                }
                try {
                    remainingNanos = idleCondition.awaitNanos(remainingNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        } finally {
            idleLock.unlock();
        }
    }

    /**
     * 同步跑完一个文档的流水线，不论它当前处于哪个非终态：正常的 {@code PENDING}，或来自启动恢复、
     * 重建中途重启的中间状态——后者会重新经过 {@code PARSING} 的 CAS 并从头重放（每个阶段都幂等）。
     *
     * <p>包级可见，是为了让 {@code KnowledgeBaseService.reindex} 能在重建线程上复用同一条流水线（裁决 R6），
     * 否则重建就得再写一份等价逻辑，两份迟早不一致。</p>
     *
     * @param documentId 文档主键
     */
    void runPipeline(long documentId) {
        DocumentStatus current;
        try {
            current = statusOf(documentId);
        } catch (RuntimeException e) {
            // 状态值损坏或无法识别时不能让文档悬着：直接置为 FAILED，并把具体取值写进 error_msg。
            log.warn("Document {} has an unreadable status", documentId, e);
            fail(documentId, "Unreadable document status: " + e.getMessage());
            return;
        }
        if (current == null) {
            log.debug("Document {} disappeared before ingestion started", documentId);
            return;
        }
        if (current.isTerminal()) {
            log.debug("Document {} is already {}; nothing to ingest", documentId, current);
            return;
        }

        try {
            // PENDING 是正常路径；中间状态来自启动恢复或重跑，既然每个阶段都幂等，就让它们重新走一遍解析。
            if (!transition(documentId, current, DocumentStatus.PARSING, null)) {
                return;
            }

            // ------------------------------------------------ 解析
            IngestRow row = loadIngestRow(documentId);
            if (storage.stat(row.objectKey()).isEmpty()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "无法解析该文件：存储中找不到已上传的文件对象 " + row.objectKey());
            }
            ParsedDocument parsed;
            try (InputStream in = storage.getObject(row.objectKey())) {
                parsed = parser.parse(in, row.filename());
            }
            // parsed_key 每次运行都覆盖写：它是恢复与排查问题时的产物（§145/§154）。
            String parsedKey = parsedKeyFor(row.objectKey());
            String markdown = parsed.markdown();
            storage.putText(parsedKey, markdown);
            jdbc.update("UPDATE documents SET parsed_key = ? WHERE id = ?", parsedKey, documentId);
            if (!transition(documentId, DocumentStatus.PARSING, DocumentStatus.CHUNKING, null)) {
                return;
            }

            // ------------------------------------------------ 切片
            ChunkConfig chunkConfig = readChunkConfig(row.kbId());
            List<TextChunk> chunks = chunker.split(parsed, chunkConfig);
            if (!transition(documentId, DocumentStatus.CHUNKING, DocumentStatus.EMBEDDING, null)) {
                return;
            }

            // ------------------------------------------------ 向量化
            Long embeddingModelId = jdbc.queryForObject(
                    "SELECT embedding_model_id FROM knowledge_bases WHERE id = ?",
                    Long.class, row.kbId());
            if (embeddingModelId == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "知识库未配置 embedding 模型，无法向量化文档");
            }
            List<String> contents = new java.util.ArrayList<>(chunks.size());
            for (TextChunk chunk : chunks) {
                contents.add(chunk.content());
            }
            // 切片为空（整篇空白，或只有标题没有正文）：仍以 READY 结束但没有任何片段；
            // replaceAll 依旧会清掉上一次运行留下的片段。
            List<float[]> vectors = contents.isEmpty()
                    ? List.of()
                    : gateway.embed(embeddingModelId, contents);
            chunkWriter.replaceAll(documentId, row.kbId(), row.nodeId(), chunks, vectors);

            transition(documentId, DocumentStatus.EMBEDDING, DocumentStatus.READY, null);
        } catch (Exception e) {
            log.warn("Document {} ingestion failed", documentId, e);
            fail(documentId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private IngestRow loadIngestRow(long documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT kb_id, node_id, filename, object_key FROM documents WHERE id = ?
                """, documentId);
        if (rows.isEmpty()) {
            // 与物理删除撞上了：当作文档已消失处理即可。
            throw new IllegalStateException("document row vanished mid-ingest");
        }
        Map<String, Object> row = rows.get(0);
        return new IngestRow(
                ((Number) row.get("kb_id")).longValue(),
                row.get("node_id") == null ? null : ((Number) row.get("node_id")).longValue(),
                (String) row.get("filename"),
                (String) row.get("object_key"));
    }

    /**
     * 计算原始对象的同级键：同目录下的 {@code /parsed.md}（裁决 R4）。
     *
     * <p>注意根级对象键（不含斜杠）应得到根级的 {@code parsed.md}，而不是 {@code doc.md/parsed.md}。</p>
     *
     * @param objectKey 原始对象键
     * @return 解析产物键
     */
    static String parsedKeyFor(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        if (slash < 0) {
            return "parsed.md";
        }
        return objectKey.substring(0, slash + 1) + "parsed.md";
    }

    /**
     * 读取并校验 {@code knowledge_bases.chunk_config}（{@code minChars}、{@code maxChars}、
     * {@code overlapRatio}）；缺失的键取 {@link ChunkConfig#DEFAULT}。
     *
     * <p>配置区间非法时按文档失败处理（{@code VALIDATION_FAILED}），不让异常穿出流水线（裁决 R7）——
     * 否则一个用户的错误配置会打断整个队列。</p>
     *
     * @param raw 原始 JSON 配置
     * @return 校验后的切片参数
     */
    ChunkConfig readChunkConfig(long kbId) {
        String json = jdbc.queryForObject(
                "SELECT chunk_config::text FROM knowledge_bases WHERE id = ?", String.class, kbId);
        if (json == null || json.isBlank()) {
            return ChunkConfig.DEFAULT;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "知识库 chunk 配置不是合法 JSON: " + json);
        }
        int minChars = node.path("minChars").asInt(ChunkConfig.DEFAULT.minChars());
        int maxChars = node.path("maxChars").asInt(ChunkConfig.DEFAULT.maxChars());
        double overlapRatio = node.path("overlapRatio").asDouble(ChunkConfig.DEFAULT.overlapRatio());
        if (minChars < 1 || maxChars < minChars || overlapRatio < 0.10 || overlapRatio > 0.15) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "无效的 chunk 配置：minChars=" + minChars + ", maxChars=" + maxChars
                            + ", overlapRatio=" + overlapRatio
                            + "（要求 minChars>=1, maxChars>=minChars, overlapRatio 在 [0.10, 0.15] 闭区间内）");
        }
        return new ChunkConfig(minChars, maxChars, overlapRatio);
    }

    private DocumentStatus statusOf(long documentId) {
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId);
        return statuses.isEmpty() ? null : DocumentStatus.from(statuses.get(0));
    }

    private void removeQuietly(long documentId, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            storage.remove(objectKey);
        } catch (RuntimeException e) {
            // 一个对象不存在，不应妨碍另一个（原始对象或解析产物）被删除。
            log.warn("Could not remove object {} of document {}: {}", objectKey, documentId, e.toString());
        }
    }

    private void submit(Runnable task, String description) {
        taskSubmitted();
        boolean handedOff = false;
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // 后台任务绝不能把线程乃至整个 JVM 一起带走。
                    log.error("Unhandled failure while trying to {}", description, t);
                } finally {
                    taskCompleted();
                }
            });
            handedOff = true;
        } catch (RuntimeException e) {
            log.error("Could not hand off task to {}", description, e);
        } finally {
            // execute() 抛出的任何异常（拒绝、已关闭等）都必须释放计数，
            // 否则后续每一次 awaitIdle() 都会白白超时。
            if (!handedOff) {
                taskCompleted();
            }
        }
    }

    private void taskSubmitted() {
        idleLock.lock();
        try {
            pendingTasks++;
        } finally {
            idleLock.unlock();
        }
    }

    private void taskCompleted() {
        idleLock.lock();
        try {
            if (pendingTasks > 0) {
                pendingTasks--;
            }
            if (pendingTasks == 0) {
                idleCondition.signalAll();
            }
        } finally {
            idleLock.unlock();
        }
    }

    private static String truncate(String errorMsg) {
        if (errorMsg == null) {
            return null;
        }
        return errorMsg.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? errorMsg
                : errorMsg.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private record IngestRow(long kbId, Long nodeId, String filename, String objectKey) {
    }

    @PreDestroy
    void shutdown() {
        reindexExecutor.shutdown();
        executor.shutdown();
    }
}
