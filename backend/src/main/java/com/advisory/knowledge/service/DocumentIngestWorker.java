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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
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
 * <p>所有文档级任务（入库、重新裁切、删除清理）都不在线程池里并发跑，而是排进
 * {@link DocumentChunkQueue}：单线程、先进先出、同一时刻只处理一个文档。上传几十个文件时它们会按提交
 * 顺序依次解析与向量化，而不是同时向 embedding 服务打出几十条请求。队列满时提交会被拒绝，文档直接置
 * {@code FAILED} 并给出可重试的文案，绝不无声丢弃。</p>
 *
 * <p>{@link #awaitIdle(Duration)} 用「计数器 + 条件变量」跟踪已提交的任务，测试因此可以等真实完成而不用睡眠。
 * 所有让计数加一的路径都在 {@code finally} 里减一，包括提交被拒绝的情况，计数器不会泄漏。
 * 知识库重建也挂在同一个计数器上（见 {@link #submitReindexTask(Runnable)}）：它把整库文档逐篇投进同一条
 * 裁切队列再等全部结束，所以重建既不会和普通入库抢 embedding，也能被 {@code awaitIdle} 等待。</p>
 */
@Component
public class DocumentIngestWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestWorker.class);

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final String INTERMEDIATE_STATUSES = "('PARSING', 'CHUNKING', 'EMBEDDING')";

    /** 队列满时的文档错误文案：用户能据此知道「不是文件坏了，是排不进去」，并且可以点重试。 */
    static final String QUEUE_FULL_MESSAGE = "裁切队列已满，请稍后重试";

    private final JdbcTemplate jdbc;
    private final StorageService storage;
    private final DocumentParser parser;
    private final StructureAwareChunker chunker;
    private final ChunkWriter chunkWriter;
    private final EmbeddingGateway gateway;
    private final ObjectMapper objectMapper;
    /** 裁切队列：单线程 FIFO，所有文档级任务都在这里串行执行。 */
    private final DocumentChunkQueue chunkQueue;
    private final ThreadPoolTaskExecutor reindexExecutor = new ThreadPoolTaskExecutor();

    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleCondition = idleLock.newCondition();
    private int pendingTasks;

    /**
     * @param jdbc           JDBC 模板
     * @param storage        对象存储，读写原始文件与解析产物
     * @param parser         文档解析器
     * @param chunker        结构化切片器
     * @param chunkWriter    片段落库
     * @param gateway        向量化网关
     * @param objectMapper   读取 {@code chunk_config}
     * @param queueCapacity  裁切队列容量，来自 {@code app.ingest.queue-capacity}
     */
    public DocumentIngestWorker(JdbcTemplate jdbc,
                                StorageService storage,
                                DocumentParser parser,
                                StructureAwareChunker chunker,
                                ChunkWriter chunkWriter,
                                EmbeddingGateway gateway,
                                ObjectMapper objectMapper,
                                @Value("${app.ingest.queue-capacity:500}") int queueCapacity) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.chunkWriter = chunkWriter;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.chunkQueue = new DocumentChunkQueue(queueCapacity);
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

    /** 提交一个文档入库任务（需要重新解析）。同一个 id 允许重复入队，最终由状态 CAS 决定谁真正执行。 */
    public void enqueue(Long documentId) {
        enqueue(documentId, IngestMode.FULL);
    }

    /**
     * 提交一次「重新裁切」：复用已落库的 {@code parsed.md}，跳过解析，直接用当前的切片配置重新切片与
     * 向量化。解析产物缺失时流水线会自动退回完整流程，因此调用方不必先检查。
     *
     * @param documentId 文档主键
     */
    public void enqueueRechunk(Long documentId) {
        enqueue(documentId, IngestMode.RECHUNK);
    }

    private void enqueue(Long documentId, IngestMode mode) {
        if (documentId == null) {
            return;
        }
        boolean accepted = submitToQueue(documentId, () -> runPipeline(documentId, mode));
        if (!accepted) {
            // 队列满时不能让文档悬在 PENDING：那会一直显示「排队中」直到下次重启。
            fail(documentId, QUEUE_FULL_MESSAGE);
        }
    }

    /**
     * 排队位次，供界面显示「排队中（第 N 位）」。
     *
     * @param documentId 文档主键
     * @return 从 1 开始的位次；正在执行或根本不在队列里返回 {@code 0}
     */
    public int queuePosition(Long documentId) {
        return documentId == null ? 0 : chunkQueue.positionOf(documentId);
    }

    /**
     * 把一批文档按给定顺序投进裁切队列，并阻塞到全部执行完毕。
     *
     * <p>供知识库重建使用（裁决 R6）：重建不再是「另开一条线程池并行跑」，而是把整库文档排进同一条
     * 裁切队列，与用户新上传的文档共用同一个执行顺序，因此重建期间不会有两套流水线同时打 embedding。
     * 计数在每篇文档结束时递减，提交被拒时立即递减并在文档上写明原因，所以等待不会挂住。</p>
     *
     * @param documentIds 文档主键，按期望的执行顺序排列
     */
    public void ingestBatch(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        CountDownLatch done = new CountDownLatch(documentIds.size());
        for (Long documentId : documentIds) {
            boolean accepted = submitToQueue(documentId, () -> {
                try {
                    runPipeline(documentId, IngestMode.FULL);
                } finally {
                    done.countDown();
                }
            });
            if (!accepted) {
                fail(documentId, QUEUE_FULL_MESSAGE);
                done.countDown();
            }
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待裁切队列清空时被中断，剩余 {} 篇文档仍在队列中", done.getCount());
        }
    }

    /**
     * 软删除后的异步清理：删掉片段、原始对象与解析产物（仅当 {@code parsed_key} 已写入时）。
     *
     * <p>在任务真正执行时会重新校验一次状态，删片段的语句内部又再校验一次，因此一个已不再是
     * {@code DELETED} 的文档，绝不会被这次清理误删掉正在入库的片段。清理同样排进裁切队列：它与同一文档
     * 的入库任务因此永远一先一后，不会出现「清理删完片段、流水线又把片段写回来」的交错。</p>
     *
     * @param documentId 文档主键
     */
    public void enqueueCleanup(Long documentId) {
        if (documentId == null) {
            return;
        }
        boolean accepted = submitToQueue(documentId, () -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status, kb_id, object_key, parsed_key FROM documents WHERE id = ?", documentId);
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
            long kbId = ((Number) row.get("kb_id")).longValue();
            removeQuietly(documentId, kbId, (String) row.get("object_key"));
            removeQuietly(documentId, kbId, (String) row.get("parsed_key"));
        });
        if (!accepted) {
            // 清理失败只是留下垃圾对象，不影响数据正确性：记一条警告，等下次删库或人工清理。
            log.warn("Document {} cleanup could not be queued; its objects stay in storage", documentId);
        }
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
     * <p>{@link IngestMode#RECHUNK} 时先尝试复用已落库的 {@code parsed.md}：命中就从 {@code CHUNKING}
     * 开始，省掉一次 Tika 解析；解析产物缺失（老数据、被清理过、对象读不出来）则自动退回
     * {@link IngestMode#FULL}，用户无事发生，只是慢一点。</p>
     *
     * <p>包级可见，是为了让 {@code KnowledgeBaseService.reindex} 能复用同一条流水线（裁决 R6），
     * 否则重建就得再写一份等价逻辑，两份迟早不一致。</p>
     *
     * @param documentId 文档主键
     * @param mode       完整入库还是只重新裁切
     */
    void runPipeline(long documentId, IngestMode mode) {
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
            IngestRow row = loadIngestRow(documentId);

            // ------------------------------------------------ 解析（重新裁切时尽量跳过）
            ParsedDocument parsed = mode == IngestMode.RECHUNK ? reuseParsed(row) : null;
            if (parsed != null) {
                if (!transition(documentId, current, DocumentStatus.CHUNKING, null)) {
                    return;
                }
            } else {
                if (mode == IngestMode.RECHUNK) {
                    log.info("Document {} has no reusable parsed text; falling back to a full parse", documentId);
                }
                // PENDING 是正常路径；中间状态来自启动恢复或重跑，既然每个阶段都幂等，就让它们重新走一遍解析。
                if (!transition(documentId, current, DocumentStatus.PARSING, null)) {
                    return;
                }
                if (storage.stat(row.kbId(), row.objectKey()).isEmpty()) {
                    throw new AppException(ErrorCode.VALIDATION_FAILED,
                            "无法解析该文件：存储中找不到已上传的文件对象 " + row.objectKey());
                }
                try (InputStream in = storage.getObject(row.kbId(), row.objectKey())) {
                    parsed = parser.parse(in, row.filename());
                }
                // parsed_key 每次运行都覆盖写：它是恢复与排查问题时的产物（§145/§154）。
                String parsedKey = parsedKeyFor(row.objectKey());
                storage.putText(row.kbId(), parsedKey, parsed.markdown());
                jdbc.update("UPDATE documents SET parsed_key = ? WHERE id = ?", parsedKey, documentId);
                if (!transition(documentId, DocumentStatus.PARSING, DocumentStatus.CHUNKING, null)) {
                    return;
                }
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
                SELECT kb_id, node_id, filename, object_key, parsed_key FROM documents WHERE id = ?
                """, documentId);
        if (rows.isEmpty()) {
            // 与物理删除撞上了：当作文档已消失处理即可。
            throw new IllegalStateException("入库过程中文档行消失了");
        }
        Map<String, Object> row = rows.get(0);
        return new IngestRow(
                ((Number) row.get("kb_id")).longValue(),
                row.get("node_id") == null ? null : ((Number) row.get("node_id")).longValue(),
                (String) row.get("filename"),
                (String) row.get("object_key"),
                (String) row.get("parsed_key"));
    }

    /**
     * 复用上一次解析留下的 Markdown，重建章节结构但不重新解析原始文件。
     *
     * <p>章节由 {@link MarkdownStructure#sections(String)} 从同一份 Markdown 重新推导，所以切片结果与
     * 完整解析路径完全一致——切片器只消费 {@code markdown} 与 {@code sections}，而两者都由这段文本决定。</p>
     *
     * @param row 文档行
     * @return 可复用的解析结果；没有产物或读不出来时返回 {@code null}，由调用方退回完整解析
     */
    private ParsedDocument reuseParsed(IngestRow row) {
        if (row.parsedKey() == null || row.parsedKey().isBlank()) {
            return null;
        }
        Optional<String> stored = storage.readText(row.kbId(), row.parsedKey());
        if (stored.isEmpty() || stored.get().isBlank()) {
            return null;
        }
        String markdown = stored.get();
        return ParsedDocument.ofMarkdown(markdown);
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

    /**
     * 删除单个存储对象；失败只记警告，不影响另一个对象（原始文件或解析产物）的清理。
     *
     * @param documentId 文档主键，仅用于日志
     * @param kbId       知识库主键，决定对象所在的桶
     * @param objectKey  对象键
     */
    private void removeQuietly(long documentId, long kbId, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            storage.remove(kbId, objectKey);
        } catch (RuntimeException e) {
            // 一个对象不存在，不应妨碍另一个（原始对象或解析产物）被删除。
            log.warn("Could not remove object {} of document {}: {}", objectKey, documentId, e.toString());
        }
    }

    /**
     * 把任务排进裁切队列，并维护 {@link #awaitIdle(Duration)} 的计数器。
     *
     * @param documentId 文档主键，用于排队位次
     * @param task       任务体
     * @return 已入队返回 {@code true}；队列满返回 {@code false}，此时计数器已经回退，调用方需自行处理文档状态
     */
    private boolean submitToQueue(long documentId, Runnable task) {
        taskSubmitted();
        boolean handedOff = chunkQueue.submit(documentId, () -> {
            try {
                task.run();
            } catch (Throwable t) {
                // 后台任务绝不能把线程乃至整个 JVM 一起带走。
                log.error("Unhandled failure while processing document {}", documentId, t);
            } finally {
                taskCompleted();
            }
        });
        if (!handedOff) {
            // 队列拒绝也必须释放计数，否则后续每一次 awaitIdle() 都会白白超时。
            taskCompleted();
        }
        return handedOff;
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

    private record IngestRow(long kbId, Long nodeId, String filename, String objectKey, String parsedKey) {
    }

    /** 入库流水线的两种入口：完整解析，或只重新裁切。 */
    enum IngestMode {
        /** 解析、切片、向量化全走一遍（上传、重试、重建索引）。 */
        FULL,
        /** 优先复用已落库的解析产物，只重新切片与向量化（单篇/批量重新裁切）。 */
        RECHUNK
    }

    @PreDestroy
    void shutdown() {
        reindexExecutor.shutdown();
        chunkQueue.shutdown();
    }
}
