package com.advisory.knowledge.service;

import com.advisory.common.api.PageResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.api.dto.ChunkResponse;
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
 * 文档上传登记、查询、重试与软删除。
 *
 * <p>上传分三步：预签名、客户端直传、再登记。登记时会 stat 上传对象并按真实大小复核 50 MB 上限；
 * 若 {@code objectKey} 已登记过，则跳过这两项检查直接返回已有行，使重试的回调天然幂等。
 * {@code ux_documents_object_key}（V2）是并发回调的兜底：唯一索引竞争的失败方会拿到 23505，
 * 转而返回成功方的那一行。对象键为 {@code kb/{kbId}/{uuid}/{净化后的文件名}}，其中 uuid 保证同名文件
 * 重复上传时键依然唯一。</p>
 *
 * <p>写操作在控制器层限管理员。读取与列表统一走 {@code JdbcTemplate}，保证返回的一定是后台 worker 刚写入的
 * 状态；用 JPA 的 {@code findById} 可能从一级缓存里取到过期实体。</p>
 *
 * <p>删除是软删除（{@code status = DELETED}），重复删除返回 404，与 id 不存在完全一致，
 * 因此接口不会泄露该行是否还在。MinIO 对象与 {@code chunks} 行由入库 worker 异步清理。</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 与 {@code application.yml} 里的 {@code spring.servlet.multipart.max-file-size} 保持一致。 */
    static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    /** 规格要求的预签名有效期。 */
    static final Duration PRESIGN_TTL = Duration.ofMinutes(10);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_FILENAME_LENGTH = 512;
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** 白名单之外的字符一律替换为 {@code _}，保证对象键在 S3/MinIO 与 URL 中都安全。 */
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

    /** 生成本次上传的对象键，并返回有效期十分钟的预签名 PUT 地址。 */
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
     * 登记一个已上传的对象。
     *
     * <p>以 {@code objectKey} 幂等：已存在时返回原文档且 {@code created = false}，不会写出重复行。
     * MinIO 的存在性检查只在插入路径上执行，因此即便对象已被消费，重放也能成功。</p>
     *
     * @param request 登记请求
     * @param userId  上传者
     * @return 文档行与「是否为新建」标记
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
            // saveAndFlush 会在入队之前先提交自己的事务：worker 跑在另一条连接上，必须能看见这一行。
            document = documents.saveAndFlush(document);
        } catch (DataIntegrityViolationException e) {
            // object_key 上的唯一索引是并发回调的兜底：失败方会等成功方提交后拿到 23505，
            // 然后改而返回成功方写入的那一行。
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
     * 幂等重放：对象已登记过，直接返回已有行，不再检查 MinIO（对象可能已被消费掉）。
     *
     * <p>若该行已被软删除，则报冲突，而不是变成一个「查不到但确实存在」的幽灵文档。</p>
     */
    private static Creation replay(Document existing) {
        if (DocumentStatus.DELETED.name().equals(existing.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "Document was deleted");
        }
        return new Creation(DocumentResponse.from(existing), false);
    }

    /** 读取文档；不存在或已软删返回 404。 */
    public DocumentResponse get(Long documentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM documents WHERE id = ? AND status <> 'DELETED'",
                        ROW_MAPPER, documentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Document not found"));
    }

    /** 分页列出知识库下的文档。 */
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

    /**
     * 按文档顺序分页返回已落库的片段。
     *
     * <p>不存在的或已软删的文档会和其它读接口一样经 {@link #get} 返回 404：已删除的文档确实没有片段可看，
     * 若假装返回空列表，一个过期的界面反而会显得一切正常。</p>
     *
     * @param documentId 文档主键
     * @param page       页码
     * @param size       每页条数
     * @return 片段分页数据
     */
    public PageResponse<ChunkResponse> listChunks(Long documentId, int page, int size) {
        get(documentId); // 与其它读接口一致：未知或已删除的文档都在这里抛 404
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE document_id = ?", Long.class, documentId);
        long totalCount = total == null ? 0 : total;

        List<ChunkResponse> items = jdbc.query("""
                        SELECT id, seq, heading_path, content, token_count
                        FROM chunks WHERE document_id = ?
                        ORDER BY seq LIMIT ? OFFSET ?
                        """,
                (rs, rowNum) -> new ChunkResponse(
                        rs.getLong("id"),
                        rs.getInt("seq"),
                        rs.getString("heading_path"),
                        rs.getString("content"),
                        rs.getObject("token_count", Integer.class)),
                documentId, pageSize, pageIndex * pageSize);

        return PageResponse.of(items, pageIndex, pageSize, totalCount);
    }

    /** 重新入队一个 {@code FAILED} 文档；其它状态一律视为冲突。 */
    public DocumentResponse retry(Long documentId) {
        // 走 JDBC 读取：worker 在别处做过比较并设置写入之后，JPA 的 findById 可能返回一级缓存里的旧实体。
        DocumentResponse current = get(documentId);
        if (!DocumentStatus.FAILED.name().equals(current.status())) {
            throw new AppException(ErrorCode.CONFLICT, "Only FAILED documents can be retried");
        }
        if (!worker.transition(documentId, DocumentStatus.FAILED, DocumentStatus.PENDING, null)) {
            throw new AppException(ErrorCode.CONFLICT, "Document status changed concurrently");
        }
        // 在 worker 再次推进状态之前，先把 PENDING 这一刻的快照读出来。
        DocumentResponse pending = get(documentId);
        worker.enqueue(documentId);
        return pending;
    }

    /** 软删除文档，并安排异步清理其对象与片段。 */
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
        // 裁决 R2（Task 7）：知识库重建索引期间拒绝上传；Task 8 的检索也走同一道闸门。
        String reindexStatus = jdbc.queryForObject(
                "SELECT reindex_status FROM knowledge_bases WHERE id = ?", String.class, kbId);
        if ("REINDEXING".equals(reindexStatus)) {
            throw new AppException(ErrorCode.KB_REINDEXING, "知识库正在重建索引，禁止上传文档");
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
     * 只把 PostgreSQL 的唯一约束冲突（SQLSTATE 23505）翻译成幂等重放。
     *
     * <p>外键与 CHECK 失败属于真正的服务端错误，绝不能被误当成「已存在的重复请求」而悄悄返回旧数据。
     * pgjdbc 是 runtime 作用域依赖，因此这里沿异常原因链用 {@link SQLException#getSQLState()} 判断，
     * 而不是引用驱动特有的异常类型。</p>
     *
     * @param e 捕获到的数据完整性异常
     * @return 是唯一约束冲突返回 {@code true}
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

    /** {@link #create} 是新建了一行还是返回了已有行，用来区分 201 与 200。 */
    public record Creation(DocumentResponse document, boolean created) {
    }
}
