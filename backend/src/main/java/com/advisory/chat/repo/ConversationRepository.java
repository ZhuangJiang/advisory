package com.advisory.chat.repo;

import com.advisory.chat.domain.Conversation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JDBC access for {@code conversations}.
 *
 * <p>{@code agent_name} is joined on every read: the conversation list is the only place the UI
 * needs it, and doing it here avoids an N+1 lookup in the service. Reads are always filtered by
 * {@code user_id} unless the caller is an administrator — the service passes {@code null} for
 * "no filter" and the SQL branches on it, so a forgotten filter cannot leak another user's
 * threads.</p>
 */
@Repository
public class ConversationRepository {

    private static final String COLUMNS = """
            c.id, c.agent_id, a.name AS agent_name, c.agent_version, c.user_id, c.title,
            c.created_at, c.updated_at
            """;

    private final JdbcTemplate jdbc;

    private final RowMapper<Conversation> rowMapper = (rs, rowNum) -> new Conversation(
            rs.getLong("id"),
            rs.getLong("agent_id"),
            rs.getString("agent_name"),
            rs.getInt("agent_version"),
            rs.getLong("user_id"),
            rs.getString("title"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long agentId, int agentVersion, long userId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO conversations (agent_id, agent_version, user_id, title)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, agentId, agentVersion, userId, title);
    }

    public Optional<Conversation> findById(long id) {
        return jdbc.query("""
                SELECT %s FROM conversations c JOIN agents a ON a.id = c.agent_id WHERE c.id = ?
                """.formatted(COLUMNS), rowMapper, id).stream().findFirst();
    }

    /** {@code userId == null} means "every user" (administrator); otherwise strictly that user. */
    public List<Conversation> findPage(Long userId, int limit, int offset) {
        String sql = """
                SELECT %s FROM conversations c JOIN agents a ON a.id = c.agent_id
                WHERE (CAST(? AS BIGINT) IS NULL OR c.user_id = CAST(? AS BIGINT))
                ORDER BY c.created_at DESC, c.id DESC
                LIMIT ? OFFSET ?
                """.formatted(COLUMNS);
        return jdbc.query(sql, rowMapper, userId, userId, limit, offset);
    }

    public long count(Long userId) {
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM conversations
                WHERE (CAST(? AS BIGINT) IS NULL OR user_id = CAST(? AS BIGINT))
                """, Long.class, userId, userId);
        return total == null ? 0 : total;
    }

    /** Bumps {@code updated_at} once a question completes, so the list sorts by activity usage. */
    public void touch(long id) {
        jdbc.update("UPDATE conversations SET updated_at = now() WHERE id = ?", id);
    }
}
