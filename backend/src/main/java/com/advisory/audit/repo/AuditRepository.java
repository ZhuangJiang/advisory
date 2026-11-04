package com.advisory.audit.repo;

import com.advisory.audit.dto.AuditConversationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only JDBC queries behind the administrator audit page.
 *
 * <p>Every filter is nullable and expressed as {@code CAST(? AS ...) IS NULL OR ...}: one statement
 * serves all sixteen combinations of {@code userId / agentId / from / to}, and — more importantly —
 * a filter that the service forgets to pass degrades to "no filter" instead of silently returning
 * the wrong rows. The conversation count and newest message time are correlated subqueries because
 * there is at most a few hundred conversations per install (spec §8) and a grouped join would need
 * two more indexes for no measurable gain.</p>
 *
 * <p>Dates filter on {@code conversations.created_at} (when the thread was opened), not on the last
 * message: the audit page is about "which threads were started in this period".</p>
 */
@Repository
public class AuditRepository {

    private static final String WHERE = """
            WHERE (CAST(? AS BIGINT) IS NULL OR c.user_id = CAST(? AS BIGINT))
              AND (CAST(? AS BIGINT) IS NULL OR c.agent_id = CAST(? AS BIGINT))
              AND (CAST(? AS TIMESTAMPTZ) IS NULL OR c.created_at >= CAST(? AS TIMESTAMPTZ))
              AND (CAST(? AS TIMESTAMPTZ) IS NULL OR c.created_at < CAST(? AS TIMESTAMPTZ))
            """;

    private static final String COLUMNS = """
            SELECT c.id, c.user_id, u.username AS user_name, c.agent_id, a.name AS agent_name,
                   c.agent_version, c.title, c.created_at,
                   (SELECT count(*) FROM messages m WHERE m.conversation_id = c.id) AS message_count,
                   (SELECT max(m.created_at) FROM messages m WHERE m.conversation_id = c.id)
                       AS last_message_at
            FROM conversations c
            JOIN users u ON u.id = c.user_id
            JOIN agents a ON a.id = c.agent_id
            """;

    private final JdbcTemplate jdbc;

    private final RowMapper<AuditConversationRow> rowMapper = (rs, rowNum) -> new AuditConversationRow(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("user_name"),
            rs.getLong("agent_id"),
            rs.getString("agent_name"),
            rs.getInt("agent_version"),
            rs.getString("title"),
            rs.getLong("message_count"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_message_at") == null
                    ? null : rs.getTimestamp("last_message_at").toInstant());

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditConversationRow> findPage(Long userId, Long agentId, Instant from,
                                               Instant toExclusive, int limit, int offset) {
        Object[] filters = filters(userId, agentId, from, toExclusive);
        Object[] args = new Object[filters.length + 2];
        System.arraycopy(filters, 0, args, 0, filters.length);
        args[filters.length] = limit;
        args[filters.length + 1] = offset;
        return jdbc.query(COLUMNS + WHERE + " ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?",
                rowMapper, args);
    }

    public long count(Long userId, Long agentId, Instant from, Instant toExclusive) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM conversations c\n" + WHERE,
                Long.class, filters(userId, agentId, from, toExclusive));
        return total == null ? 0 : total;
    }

    public Optional<AuditConversationRow> findRow(long id) {
        return jdbc.query(COLUMNS + " WHERE c.id = ?", rowMapper, id).stream().findFirst();
    }

    /** Used for the {@code adminAudit} log line: an id alone is not an audit trail. */
    public Optional<String> findUsername(long userId) {
        return jdbc.queryForList("SELECT username FROM users WHERE id = ?", String.class, userId)
                .stream().findFirst();
    }

    private static Object[] filters(Long userId, Long agentId, Instant from, Instant toExclusive) {
        Timestamp fromTs = from == null ? null : Timestamp.from(from);
        Timestamp toTs = toExclusive == null ? null : Timestamp.from(toExclusive);
        return new Object[]{userId, userId, agentId, agentId, fromTs, fromTs, toTs, toTs};
    }
}
