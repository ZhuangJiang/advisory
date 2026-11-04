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
 * {@code messages} 的 JDBC 访问层。
 *
 * <p>助手消息在调用模型之前就以 {@code PENDING} 插入，这样即便客户端立刻断开也留下一条可恢复的行——
 * 首个 SSE 帧（{@code meta}）携带的正是这个行 id。状态流转用独立语句而非实体更新，
 * 是为了不让一条长时间的流占着事务不放。</p>
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

    /** 插入消息，助手消息初始为 PENDING。 */
    public long insert(long conversationId, String role, String content, MessageStatus status) {
        return jdbc.queryForObject("""
                INSERT INTO messages (conversation_id, role, content, status)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, conversationId, role, content, status.name());
    }

    /** 读取单条消息。 */
    public Optional<Message> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM messages WHERE id = ?", rowMapper, id)
                .stream().findFirst();
    }

    /** 读取会话内的消息，按时间正序。 */
    public List<Message> findByConversation(long conversationId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM messages WHERE conversation_id = ? ORDER BY created_at, id",
                rowMapper, conversationId);
    }

    /** 统计会话内的消息数。 */
    public long countByConversation(long conversationId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?", Long.class, conversationId);
        return count == null ? 0 : count;
    }

    /** 推进中间状态（PENDING → STREAMING）。 */
    public void updateStatus(long id, MessageStatus status) {
        jdbc.update("UPDATE messages SET status = ? WHERE id = ?", status.name(), id);
    }

    /** 成功终态：答案、引用、用量、耗时与状态在一条语句里写定。 */
    public void complete(long id, String content, List<RetrievedChunk> citations,
                         TokenUsage usage, long latencyMs, MessageStatus status) {
        jdbc.update("""
                UPDATE messages
                SET content = ?, citations = CAST(? AS jsonb), token_usage = CAST(? AS jsonb),
                    latency_ms = ?, status = ?
                WHERE id = ?
                """, content, writeJson(citations), writeJson(usage), latencyMs, status.name(), id);
    }

    /** 失败终态：保留提供商返回的信息，让用户知道为什么没有结果。 */
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
            // 引用数据解析失败时不能连累答案本身：宁可少显示引用，也要把回答展示出来。
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
