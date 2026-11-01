package com.advisory.knowledge.service;

import com.advisory.common.api.PageResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.api.dto.CreateDocumentRequest;
import com.advisory.knowledge.api.dto.DocumentResponse;
import com.advisory.knowledge.api.dto.PresignRequest;
import com.advisory.knowledge.api.dto.PresignResponse;
import com.advisory.knowledge.domain.Document;
import com.advisory.knowledge.domain.DocumentStatus;
import com.advisory.knowledge.domain.KbNodeRow;
import com.advisory.knowledge.repo.DocumentRepository;
import com.advisory.knowledge.repo.KbNodeJdbcRepository;
import com.advisory.knowledge.repo.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.net.URLConnection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Document upload registration, reads, retry and soft delete.
 *
 * <p>The upload is three steps: presign, client-side PUT, then create. Create stats the uploaded
 * object and checks its real size against the 50 MB limit (a replay of an already registered
 * {@code objectKey} skips both checks and returns the existing row, which makes a retried callback
 * safe). {@code ux_documents_object_key} (V2) backstops concurrent callbacks: the loser of the
 * unique-index race gets 23505 and returns the winner's row. The object key is
 * {@code kb/{kbId}/{uuid}/{sanitized filename}}; the uuid keeps the keys unique even for repeated
 * uploads of the same filename.</p>
 *
 * <p>Writes are ADMIN-only at the controller. Reads and listing go through {@code JdbcTemplate} so
 * the status written by the background worker is always the one returned; a JPA {@code findById}
 * could serve a stale entity from the persistence context.</p>
 *
 * <p>Deleting is a soft delete ({@code status = DELETED}) and a second delete answers 404, the same
 * as an unknown id, so the endpoint does not leak whether the row is still there. The MinIO object
 * and the {@code chunks} rows are removed asynchronously by the ingest worker.</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** Matches {@code spring.servlet.multipart.max-file-size} in {@code application.yml}. */
    static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    /** MinIO presign lifetime required by the spec. */
    static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_FILENAME_LENGTH = 512;
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** Everything outside this set becomes {@code _}; it keeps keys safe for S3/MinIO and for URLs. */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    private static final String COLUMNS = """
            id, kb_id, node_id, filename, object_key, mime, size, checksum, status, error_msg,
            created_at, updated_at
            """;

    private static final RowMapper<DocumentResponse> ROW_MAPPER = (rs, rowNum) -> new DocumentResponse(
            rs.getLong("id"),
            rs.getLong("kb_id"),
            rs.getObject("node_id", Long.class),
            rs.getString("filename"),
            rs.getString("object_key"),
            rs.getString("mime"),
            rs.getLong("size"),
            rs.getString("checksum"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final DocumentRepository documents;
    private final KnowledgeBaseRepository knowledgeBases;
    private final KbNodeJdbcRepository nodes;
    private final StorageService storage;
    private final DocumentIngestWorker worker;
    private final JdbcTemplate jdbc;

    public DocumentService(DocumentRepository documents,
                           KnowledgeBaseRepository knowledgeBases,
                           KbNodeJdbcRepository nodes,
                           StorageService storage,
                           DocumentIngestWorker worker,
                           JdbcTemplate jdbc) {
        this.documents = documents;
        this.knowledgeBases = knowledgeBases;
        this.nodes = nodes;
        this.storage = storage;
        this.worker = worker;
        this.jdbc = jdbc;
    }

    /** Creates the object key for this upload and returns a ten-minute presigned PUT URL. */
    public PresignResponse presign(PresignRequest request) {
        Long kbId = requireKnowledgeBase(request.kbId());
        validateNodeBelongsToKb(kbId, request.nodeId());
        String filename = validateFilename(request.filename());
        validateSize(request.size());

        String objectKey = newObjectKey(kbId, filename);
        String uploadUrl = storage.presignPut(objectKey, PRESIGN_TTL);
        return new PresignResponse(uploadUrl, objectKey, PRESIGN_TTL.toSeconds());
    }

    /**
     * Registers an uploaded object. Idempotent on {@code objectKey}: an existing document is returned
     * with {@code created = false} and no duplicate row is written. The MinIO existence check only
     * runs on the insert path, so a replay succeeds even if the object was already consumed.
     */
    public Creation create(CreateDocumentRequest request, Long userId) {
        Long kbId = requireKnowledgeBase(request.kbId());
        validateNodeBelongsToKb(kbId, request.nodeId());
        String filename = validateFilename(request.filename());
        validateSize(request.size());
        String objectKey = validateObjectKey(kbId, request.objectKey());

        Document existing = documents.findByObjectKey(objectKey).orElse(null);
        if (existing != null) {
            return replay(existing);
        }

        long actualSize = storage.stat(objectKey)
                .orElseThrow(() -> new AppException(ErrorCode.VALIDATION_FAILED,
                        "Uploaded object not found; upload the file before creating the document"))
                .size();
        if (actualSize > MAX_SIZE_BYTES) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Uploaded object must not exceed " + MAX_SIZE_BYTES + " bytes");
        }

        Document document = new Document(kbId, request.nodeId(), filename, objectKey);
        document.setSize(request.size() == null ? 0L : request.size());
        document.setChecksum(trimToNull(request.checksum()));
        document.setMime(guessMime(filename));
        document.setCreatedBy(userId);
        document.setStatus(DocumentStatus.PENDING.name());
        try {
            // saveAndFlush commits in its own transaction before enqueue: the worker runs on another
            // connection and must see the row.
            document = documents.saveAndFlush(document);
        } catch (DataIntegrityViolationException e) {
            // The unique index on object_key is the backstop for two concurrent callbacks: the loser
            // waits for the winner to commit, then gets 23505 and must return the winner's row.
            if (!isUniqueViolation(e)) {
                throw e;
            }
            Document winner = documents.findByObjectKey(objectKey).orElseThrow(() -> e);
            log.info("Concurrent create for object_key {} lost the unique-index race; returning document {}",
                    objectKey, winner.getId());
            return replay(winner);
        }

        worker.enqueue(document.getId());
        return new Creation(DocumentResponse.from(document), true);
    }

    /**
     * Idempotent replay: the object was already registered, so return the existing row without
     * re-checking MinIO (the object may already have been consumed). A soft-deleted row is a
     * conflict instead of a silently invisible document.
     */
    private static Creation replay(Document existing) {
        if (DocumentStatus.DELETED.name().equals(existing.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "Document was deleted");
        }
        return new Creation(DocumentResponse.from(existing), false);
    }

    public DocumentResponse get(Long documentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM documents WHERE id = ? AND status <> 'DELETED'",
                        ROW_MAPPER, documentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Document not found"));
    }

    public PageResponse<DocumentResponse> list(Long kbId, Long nodeId, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        StringBuilder where = new StringBuilder(" WHERE status <> 'DELETED'");
        List<Object> args = new ArrayList<>();
        if (kbId != null) {
            where.append(" AND kb_id = ?");
            args.add(kbId);
        }
        if (nodeId != null) {
            where.append(" AND node_id = ?");
            args.add(nodeId);
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM documents" + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;

        args.add(pageSize);
        args.add(pageIndex * pageSize);
        List<DocumentResponse> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM documents" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, args.toArray());

        return PageResponse.of(items, pageIndex, pageSize, totalCount);
    }

    /** Re-queues a {@code FAILED} document. Any other status is a conflict. */
    public DocumentResponse retry(Long documentId) {
        // Read through JDBC: a JPA findById could serve a stale entity after the worker's
        // out-of-band compare-and-set writes.
        DocumentResponse current = get(documentId);
        if (!DocumentStatus.FAILED.name().equals(current.status())) {
            throw new AppException(ErrorCode.CONFLICT, "Only FAILED documents can be retried");
        }
        if (!worker.transition(documentId, DocumentStatus.FAILED, DocumentStatus.PENDING, null)) {
            throw new AppException(ErrorCode.CONFLICT, "Document status changed concurrently");
        }
        // Read the PENDING snapshot before the worker can move it again.
        DocumentResponse pending = get(documentId);
        worker.enqueue(documentId);
        return pending;
    }

    /** Soft-deletes the document and schedules removal of its object and chunks. */
    public void delete(Long documentId) {
        int updated = jdbc.update("""
                UPDATE documents SET status = 'DELETED', updated_at = now()
                WHERE id = ? AND status <> 'DELETED'
                """, documentId);
        if (updated == 0) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Document not found");
        }

        worker.enqueueCleanup(documentId);
        log.info("Soft-deleted document {}; deferred cleanup queued", documentId);
    }

    private Long requireKnowledgeBase(Long kbId) {
        if (kbId == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "kbId must not be null");
        }
        if (!knowledgeBases.existsById(kbId)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Knowledge base not found");
        }
        return kbId;
    }

    private void validateNodeBelongsToKb(Long kbId, Long nodeId) {
        if (nodeId == null) {
            return;
        }
        KbNodeRow node = nodes.findById(nodeId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Node not found"));
        if (!node.kbId().equals(kbId)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Node not found in this knowledge base");
        }
    }

    private static String validateFilename(String raw) {
        if (raw == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "filename must not be blank");
        }
        String filename = raw.trim();
        if (filename.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "filename must not be blank");
        }
        if (filename.length() > MAX_FILENAME_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "filename must not exceed " + MAX_FILENAME_LENGTH + " characters");
        }
        for (int i = 0; i < filename.length(); i++) {
            if (Character.isISOControl(filename.charAt(i))) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "filename must not contain control characters");
            }
        }
        return filename;
    }

    private static void validateSize(Long size) {
        if (size != null && size > MAX_SIZE_BYTES) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "size must not exceed " + MAX_SIZE_BYTES + " bytes");
        }
    }

    private static String validateObjectKey(Long kbId, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "objectKey must not be blank");
        }
        String objectKey = raw.trim();
        String prefix = "kb/" + kbId + "/";
        if (!objectKey.startsWith(prefix)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "objectKey must belong to knowledge base " + kbId);
        }
        if (objectKey.contains("..") || objectKey.contains("\\")) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "objectKey contains an illegal segment");
        }
        if (objectKey.length() > 1024) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "objectKey must not exceed 1024 characters");
        }
        return objectKey;
    }

    private static String newObjectKey(Long kbId, String filename) {
        String sanitized = sanitizeFilename(filename);
        if (sanitized.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "filename must contain at least one of [A-Za-z0-9._-]");
        }
        return "kb/" + kbId + "/" + UUID.randomUUID() + "/" + sanitized;
    }

    private static String sanitizeFilename(String filename) {
        return UNSAFE_FILENAME_CHARS.matcher(filename.trim()).replaceAll("_");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String guessMime(String filename) {
        String guessed = URLConnection.guessContentTypeFromName(filename);
        if (guessed != null && guessed.length() <= 255) {
            return guessed;
        }
        return null;
    }

    /**
     * Translates only PostgreSQL unique violations (SQLSTATE 23505). FK/CHECK failures are real
     * server errors and must not be mistaken for an idempotent replay. The pgjdbc driver is
     * runtime-scoped, so the cause chain is inspected through {@link SQLException#getSQLState()}.
     */
    private static boolean isUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) {
                return true;
            }
            if (cause instanceof SQLException sqlException
                    && SQLSTATE_UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** Whether {@link #create} inserted a row or returned an existing one (200 vs 201). */
    public record Creation(DocumentResponse document, boolean created) {
    }
}
