package com.advisory.chat.repo;

import com.advisory.chat.domain.Message;
import com.advisory.chat.domain.MessageStatus;
import com.advisory.chat.orchestration.TokenUsage;
import com.advisory.knowledge.retrieval.RetrievedChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JDBC access for {@code messages}.
 *
 * <p>The assistant row is inserted as {@code PENDING} before the model is called, so a client that
 * disconnects immediately still leaves a recoverable row — that row id is what the first SSE frame
 * ({@code meta}) carries. Status transitions are separate statements rather than an entity update,
 * which keeps a long-running stream from holding a transaction open.</p>
 */
@Repository
public class MessageRepository {

    private static final String COLUMNS = """
            id, conversation_id, role, content, citations::text AS citations, status,
            token_usage::text AS token_usage, latency_ms, created_at
            """;

    private static final TypeReference<List<RetrievedChunk>> CITATIONS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final RowMapper<Message> rowMapper = (rs, rowNum) -> new Message(
            rs.getLong("id"),
            rs.getLong("conversation_id"),
            rs.getString("role"),
            rs.getString("content"),
            readCitations(rs.getString("citations")),
            MessageStatus.valueOf(rs.getString("status")),
            readUsage(rs.getString("token_usage")),
            rs.getObject("latency_ms", Long.class),
            rs.getTimestamp("created_at").toInstant());

    public MessageRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long insert(long conversationId, String role, String content, MessageStatus status) {
        return jdbc.queryForObject("""
                INSERT INTO messages (conversation_id, role, content, status)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, conversationId, role, content, status.name());
    }

    public Optional<Message> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM messages WHERE id = ?", rowMapper, id)
                .stream().findFirst();
    }

    public List<Message> findByConversation(long conversationId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM messages WHERE conversation_id = ? ORDER BY created_at, id",
                rowMapper, conversationId);
    }

    public long countByConversation(long conversationId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?", Long.class, conversationId);
        return count == null ? 0 : count;
    }

    public void updateStatus(long id, MessageStatus status) {
        jdbc.update("UPDATE messages SET status = ? WHERE id = ?", status.name(), id);
    }

    /** Terminal success path: answer, citations, usage, latency and status in one statement. */
    public void complete(long id, String content, List<RetrievedChunk> citations,
                         TokenUsage usage, long latencyMs, MessageStatus status) {
        jdbc.update("""
                UPDATE messages
                SET content = ?, citations = CAST(? AS jsonb), token_usage = CAST(? AS jsonb),
                    latency_ms = ?, status = ?
                WHERE id = ?
                """, content, writeJson(citations), writeJson(usage), latencyMs, status.name(), id);
    }

    /** Terminal failure path: keep the provider message so the user sees why nothing came back. */
    public void fail(long id, String content, long latencyMs) {
        jdbc.update("""
                UPDATE messages
                SET content = ?, status = ?, latency_ms = ?
                WHERE id = ?
                """, content, MessageStatus.FAILED.name(), latencyMs, id);
    }

    private List<RetrievedChunk> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CITATIONS);
        } catch (Exception e) {
            // An unreadable citation blob must not hide the answer it belongs to.
            return List.of();
        }
    }

    private TokenUsage readUsage(String json) {
        if (json == null || json.isBlank()) {
            return TokenUsage.ZERO;
        }
        try {
            return objectMapper.readValue(json, TokenUsage.class);
        } catch (Exception e) {
            return TokenUsage.ZERO;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
