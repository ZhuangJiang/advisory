package com.advisory.knowledge.service;

import com.advisory.knowledge.domain.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Asynchronous document ingest queue and the document status machine.
 *
 * <p>All status writes are compare-and-set: {@code UPDATE documents SET status = ? WHERE id = ? AND
 * status = ?}. Zero affected rows means another actor already moved the document, so the task logs
 * and abandons the work instead of overwriting a newer state. The pipeline is deliberately a stub in
 * this task — it walks {@code PENDING -> PARSING -> CHUNKING -> EMBEDDING -> READY} without parsing,
 * chunking or embedding, so a document is {@code READY} with zero chunks.</p>
 *
 * <p>Restart recovery is a startup {@link ApplicationRunner}: the backend is single-replica, so any
 * row left in an intermediate status belongs to a dead process. Rows are reset to {@code PENDING}
 * and re-queued; terminal rows are left alone.</p>
 *
 * <p>{@link #awaitIdle(Duration)} tracks submitted tasks with a counter guarded by a condition
 * variable, so tests wait for real completion instead of sleeping. Every path that increments the
 * counter decrements it in a {@code finally} block, including rejected submissions, so the counter
 * cannot leak.</p>
 */
@Component
public class DocumentIngestWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestWorker.class);

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final String INTERMEDIATE_STATUSES = "('PARSING', 'CHUNKING', 'EMBEDDING')";

    private final JdbcTemplate jdbc;
    private final StorageService storage;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleCondition = idleLock.newCondition();
    private int pendingTasks;

    public DocumentIngestWorker(JdbcTemplate jdbc, StorageService storage) {
        this.jdbc = jdbc;
        this.storage = storage;
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("doc-ingest-");
        executor.initialize();
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
     * queues them. Returns the ids that were re-queued (in id order).
     */
    public List<Long> recoverInterrupted() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM documents WHERE status IN " + INTERMEDIATE_STATUSES + " ORDER BY id", Long.class);
        if (ids.isEmpty()) {
            return List.of();
        }
        int reset = jdbc.update(
                "UPDATE documents SET status = 'PENDING', updated_at = now() WHERE status IN " + INTERMEDIATE_STATUSES);
        log.info("Reset {} interrupted document(s) to PENDING", reset);
        ids.forEach(this::enqueue);
        return ids;
    }

    /** Submits a document for ingestion. Re-enqueueing the same id is allowed (status CAS decides). */
    public void enqueue(Long documentId) {
        if (documentId == null) {
            return;
        }
        submit(() -> process(documentId), "ingest document " + documentId);
    }

    /** Fire-and-forget removal of the MinIO object and chunks after a soft delete. */
    public void enqueueCleanup(Long documentId, String objectKey) {
        submit(() -> {
            jdbc.update("DELETE FROM chunks WHERE document_id = ?", documentId);
            if (objectKey != null && !objectKey.isBlank()) {
                storage.remove(objectKey);
            }
        }, "clean up document " + documentId);
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

    private void process(long documentId) {
        DocumentStatus current = statusOf(documentId);
        if (current == null) {
            log.debug("Document {} disappeared before ingestion started", documentId);
            return;
        }
        if (current.isTerminal()) {
            log.debug("Document {} is already {}; nothing to ingest", documentId, current);
            return;
        }

        try {
            DocumentStatus from = current;
            if (from == DocumentStatus.PENDING) {
                if (!transition(documentId, DocumentStatus.PENDING, DocumentStatus.PARSING, null)) {
                    return;
                }
                from = DocumentStatus.PARSING;
            }
            if (from == DocumentStatus.PARSING) {
                // Task 6 replaces this step with real parsing.
                if (!transition(documentId, DocumentStatus.PARSING, DocumentStatus.CHUNKING, null)) {
                    return;
                }
                from = DocumentStatus.CHUNKING;
            }
            if (from == DocumentStatus.CHUNKING) {
                // Task 7 replaces this step with real chunking + embedding.
                if (!transition(documentId, DocumentStatus.CHUNKING, DocumentStatus.EMBEDDING, null)) {
                    return;
                }
                from = DocumentStatus.EMBEDDING;
            }
            if (from == DocumentStatus.EMBEDDING) {
                transition(documentId, DocumentStatus.EMBEDDING, DocumentStatus.READY, null);
            }
        } catch (Exception e) {
            log.warn("Document {} ingestion failed", documentId, e);
            fail(documentId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private DocumentStatus statusOf(long documentId) {
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM documents WHERE id = ?", String.class, documentId);
        return statuses.isEmpty() ? null : DocumentStatus.from(statuses.get(0));
    }

    private void submit(Runnable task, String description) {
        taskSubmitted();
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
        } catch (TaskRejectedException e) {
            taskCompleted();
            log.error("Ingest queue is full; dropping task to {}", description, e);
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

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
