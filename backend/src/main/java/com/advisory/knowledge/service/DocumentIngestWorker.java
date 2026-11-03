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
 * Asynchronous document ingest queue and the document status machine.
 *
 * <p>All status writes are compare-and-set: {@code UPDATE documents SET status = ? WHERE id = ? AND
 * status = ?}. Zero affected rows means another actor already moved the document, so the task logs
 * and abandons the work instead of overwriting a newer state. The pipeline walks
 * {@code PENDING -> PARSING -> CHUNKING -> EMBEDDING -> READY}: parse (store {@code parsed_key} on
 * MinIO), structure-aware chunking with the knowledge base's {@code chunk_config}, embedding of
 * every chunk and an idempotent chunk {@code replaceAll}. A document with an empty chunk list
 * becomes {@code READY} with zero chunks; every other failure becomes {@code FAILED} with a
 * truncated {@code error_msg}.</p>
 *
 * <p>Restart recovery is a startup {@link ApplicationRunner}: the backend is single-replica, so any
 * row left in an intermediate status belongs to a dead process. Rows are reset to {@code PENDING}
 * and re-queued; terminal rows are left alone. A resumed document is moved back behind the
 * {@code PARSING} CAS: every stage is idempotent (the parsed object is overwritten, the chunk set
 * is {@code replaceAll}ed), so replaying from the front is always safe.</p>
 *
 * <p>{@link #awaitIdle(Duration)} tracks submitted tasks with a counter guarded by a condition
 * variable, so tests wait for real completion instead of sleeping. Every path that increments the
 * counter decrements it in a {@code finally} block, including rejected submissions, so the counter
 * cannot leak. The same counter tracks knowledge-base reindex tasks on the dedicated single-thread
 * executor ({@link #submitReindexTask(Runnable)}), so a reindex can be awaited exactly like a
 * normal ingest.</p>
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
        // Reindex serializes whole knowledge-base rebuilds; queue is per design (ruling R6).
        reindexExecutor.setCorePoolSize(1);
        reindexExecutor.setMaxPoolSize(1);
        reindexExecutor.setQueueCapacity(100);
        reindexExecutor.setThreadNamePrefix("kb-reindex-");
        reindexExecutor.initialize();
    }

    /** Startup hook: re-queue documents that a previous process left mid-pipeline. */
    @Override
    public void run(ApplicationArguments args) {
        int requeued = recoverInterrupted().size();
        if (requeued > 0) {
            log.info("Re-queued {} document(s) left in an intermediate state by the previous run", requeued);
        }
    }

    /**
     * Resets rows stuck in {@code PARSING}/{@code CHUNKING}/{@code EMBEDDING} to {@code PENDING} and
     * queues them. Returns the ids that were re-queued (in the order PostgreSQL returned them).
     *
     * <p>One {@code UPDATE ... RETURNING id} statement instead of select-then-update: with two
     * statements a worker could legitimately move a document from {@code PENDING} to {@code PARSING}
     * between them, be reset to {@code PENDING} without appearing in the returned id list, and then
     * have its compare-and-set fail — stranding the document with no API path out. Calling this
     * method twice is safe: the second call only sees rows that are still intermediate (the first
     * call's tasks may already have advanced them, in which case they are reset and re-queued again).</p>
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

    /** Submits a document for ingestion. Re-enqueueing the same id is allowed (status CAS decides). */
    public void enqueue(Long documentId) {
        if (documentId == null) {
            return;
        }
        submit(() -> runPipeline(documentId), "ingest document " + documentId);
    }

    /**
     * Fire-and-forget cleanup after a soft delete: removes the chunks, the raw object and the parsed
     * object (when Task 6 has filled {@code parsed_key}).
     *
     * <p>The status is re-checked when the task actually runs, and the chunk delete repeats that
     * check inside the statement, so a document that is no longer {@code DELETED} can never lose the
     * chunks of a live ingest.</p>
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
     * Submits a knowledge-base reindex on the dedicated single-thread executor, tracked by the same
     * {@link #awaitIdle(Duration)} counter so tests wait for real completion. The runnable is fully
     * responsible for settling the knowledge base status in its own {@code finally}.
     *
     * @return {@code true} once handed off to the executor; {@code false} when the executor rejected
     * the task (the caller must then restore the knowledge base status, ruling R6)
     */
    public boolean submitReindexTask(Runnable task) {
        taskSubmitted();
        boolean handedOff = false;
        try {
            reindexExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // The runnable settles the KB status itself; this is the last line of defense.
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
     * Compare-and-set transition. Returns {@code false} when the row is no longer in {@code from}
     * (someone else moved it), which callers treat as "abandon this work item", not as an error.
     * A {@code null} {@code errorMsg} clears the column.
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

    /** Moves the document to {@code FAILED} unless it is already terminal. */
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
     * Waits until every submitted task has finished. Returns {@code false} on timeout or interrupt.
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
     * Runs the pipeline synchronously for one document, whatever non-terminal status it currently
     * has: {@code PENDING} (normal path) or an intermediate status from start-up recovery or a
     * mid-reindex restart, which is moved back behind the {@code PARSING} CAS and replayed from the
     * front (every stage is idempotent). Package-private so {@code KnowledgeBaseService.reindex} can
     * drive the exact same pipeline synchronously on the reindex thread (ruling R6).
     */
    void runPipeline(long documentId) {
        DocumentStatus current;
        try {
            current = statusOf(documentId);
        } catch (RuntimeException e) {
            // A corrupt/unreadable status must not leave the document in limbo; mark it FAILED and
            // surface the value in error_msg.
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
            // PENDING is the normal path; an intermediate status comes from start-up recovery or a
            // re-run: send it back through parsing since every stage is idempotent.
            if (!transition(documentId, current, DocumentStatus.PARSING, null)) {
                return;
            }

            // ------------------------------------------------ PARSING
            IngestRow row = loadIngestRow(documentId);
            if (storage.stat(row.objectKey()).isEmpty()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "无法解析该文件：存储中找不到已上传的文件对象 " + row.objectKey());
            }
            ParsedDocument parsed;
            try (InputStream in = storage.getObject(row.objectKey())) {
                parsed = parser.parse(in, row.filename());
            }
            // parsed_key is overwritten on every run: it is the recovery/debug artifact (§145/§154).
            String parsedKey = parsedKeyFor(row.objectKey());
            String markdown = parsed.markdown();
            storage.putText(parsedKey, markdown);
            jdbc.update("UPDATE documents SET parsed_key = ? WHERE id = ?", parsedKey, documentId);
            if (!transition(documentId, DocumentStatus.PARSING, DocumentStatus.CHUNKING, null)) {
                return;
            }

            // ------------------------------------------------ CHUNKING
            ChunkConfig chunkConfig = readChunkConfig(row.kbId());
            List<TextChunk> chunks = chunker.split(parsed, chunkConfig);
            if (!transition(documentId, DocumentStatus.CHUNKING, DocumentStatus.EMBEDDING, null)) {
                return;
            }

            // ------------------------------------------------ EMBEDDING
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
            // Empty chunk list (whitespace-only document or headings without prose): READY with zero
            // chunks; replaceAll still deletes any chunks from a previous run.
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
            // Raced against a hard delete: act as if the document vanished.
            throw new IllegalStateException("document row vanished mid-ingest");
        }
        Map<String, Object> row = rows.get(0);
        return new IngestRow(
                ((Number) row.get("kb_id")).longValue(),
                row.get("node_id") == null ? null : ((Number) row.get("node_id")).longValue(),
                (String) row.get("filename"),
                (String) row.get("object_key"));
    }

    /** Sibling key of the raw object: same directory + {@code /parsed.md} (ruling R4). */
    static String parsedKeyFor(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        if (slash < 0) {
            return objectKey + "/parsed.md";
        }
        return objectKey.substring(0, slash + 1) + "parsed.md";
    }

    /**
     * Reads and validates {@code knowledge_bases.chunk_config} ({@code minChars}, {@code maxChars},
     * {@code overlapRatio}); missing keys fall back to {@link ChunkConfig#DEFAULT}; an invalid range
     * is a document failure ({@code VALIDATION_FAILED}), not an escaping exception (ruling R7).
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
        if (minChars < 1 || maxChars < minChars || overlapRatio < 0 || overlapRatio >= 0.5) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "无效的 chunk 配置：minChars=" + minChars + ", maxChars=" + maxChars
                            + ", overlapRatio=" + overlapRatio + "（要求 minChars>=1, maxChars>=minChars, 0<=overlapRatio<0.5）");
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
            // One missing object must not stop the other (raw vs parsed) from being removed.
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
                    // A background task must never take the thread (or the JVM) down with it.
                    log.error("Unhandled failure while trying to {}", description, t);
                } finally {
                    taskCompleted();
                }
            });
            handedOff = true;
        } catch (RuntimeException e) {
            log.error("Could not hand off task to {}", description, e);
        } finally {
            // Any exception out of execute() (rejection, shutdown, ...) must release the counter,
            // otherwise every later awaitIdle() would time out.
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
