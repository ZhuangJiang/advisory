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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Knowledge base lifecycle and listing.
 *
 * <p>Listing is done with {@code JdbcTemplate} so the per-row {@code documentCount} is resolved by a
 * {@code LEFT JOIN LATERAL} in a single query; the {@code documents} table has no JPA entity yet.
 * Deletion rejects knowledge bases that are still referenced by an {@code agents} row (also no entity
 * yet) or that are mid-reindex.</p>
 */
@Service
public class KnowledgeBaseService {

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_PAGE_SIZE = 100;

    private static final String LIST_SQL = """
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
            ORDER BY kb.id
            LIMIT ? OFFSET ?
            """;

    private final KnowledgeBaseRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseService(KnowledgeBaseRepository repository, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest body, Long createdBy) {
        String name = validateName(body.name(), MAX_NAME_LENGTH);
        KnowledgeBase kb = new KnowledgeBase(name);
        kb.setDescription(normalizeDescription(body.description()));
        kb.setEmbeddingModelId(body.embeddingModelId());
        kb.setChunkConfig(normalizeChunkConfig(body.chunkConfig()));
        kb.setCreatedBy(createdBy);
        kb = repository.save(kb);
        return KnowledgeBaseResponse.from(kb);
    }

    @Transactional
    public KnowledgeBaseResponse update(Long id, UpdateKnowledgeBaseRequest body) {
        KnowledgeBase kb = find(id);
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

    @Transactional
    public void delete(Long id) {
        KnowledgeBase kb = find(id);
        if ("REINDEXING".equals(kb.getReindexStatus())) {
            throw new AppException(ErrorCode.KB_REINDEXING, "Knowledge base is reindexing");
        }
        // kb_ids may store ids as JSON numbers or strings ("5"); compare the text elements as bigints
        // instead of relying on jsonb containment with a single representation.
        Long references = jdbc.queryForObject("""
                SELECT count(*)
                FROM agents a
                CROSS JOIN LATERAL jsonb_array_elements_text(a.kb_ids) AS e(elem)
                WHERE e.elem ~ '^-?[0-9]+$' AND e.elem::bigint = ?
                """, Long.class, id);
        if (references != null && references > 0) {
            throw new AppException(ErrorCode.KB_IN_USE, "Knowledge base is referenced by an agent");
        }
        repository.delete(kb);
    }

    @Transactional(readOnly = true)
    public PageResponse<KnowledgeBaseResponse> list(int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        Long total = jdbc.queryForObject("SELECT count(*) FROM knowledge_bases", Long.class);
        long totalCount = total == null ? 0 : total;

        List<KnowledgeBaseResponse> items = jdbc.query(LIST_SQL, (rs, rowNum) -> new KnowledgeBaseResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                (Long) rs.getObject("embedding_model_id"),
                rs.getString("chunk_config"),
                rs.getString("reindex_status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("document_count")), pageSize, pageIndex * pageSize);

        return PageResponse.of(items, pageIndex, pageSize, totalCount);
    }

    private KnowledgeBase find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Knowledge base not found"));
    }

    private static String validateName(String raw, int maxLength) {
        if (raw == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not be blank");
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not be blank");
        }
        if (name.length() > maxLength) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not exceed " + maxLength + " characters");
        }
        rejectControlCharacters(name);
        return name;
    }

    private static void rejectControlCharacters(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "name must not contain control characters");
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
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chunkConfig must be valid JSON");
        }
        return trimmed;
    }
}
