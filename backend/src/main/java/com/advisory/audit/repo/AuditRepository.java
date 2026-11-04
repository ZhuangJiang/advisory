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
 * 管理员审计页背后的只读 JDBC 查询。
 *
 * <p>每个筛选条件都可空，统一写成 {@code CAST(? AS ...) IS NULL OR ...}：一条语句就能覆盖
 * {@code userId / agentId / from / to} 的全部十六种组合；更重要的是，服务层漏传某个参数时会退化为
 * 「不筛选」，而不是悄悄返回一批错误的数据。会话数与最后消息时间用相关子查询实现——单套部署最多几百个会话
 * （spec §8），改成聚合 join 还要多建两个索引，收益无法测量。</p>
 *
 * <p>时间范围按 {@code conversations.created_at}（会话创建时间）过滤，而不是最后一条消息的时间：
 * 这个页面回答的是「这段时间里新开了哪些会话」。</p>
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

    /** 分页查询会话，四个筛选条件均可为空。 */
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

    /** 统计满足条件的会话数。 */
    public long count(Long userId, Long agentId, Instant from, Instant toExclusive) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM conversations c\n" + WHERE,
                Long.class, filters(userId, agentId, from, toExclusive));
        return total == null ? 0 : total;
    }

    /** 读取单条会话头信息。 */
    public Optional<AuditConversationRow> findRow(long id) {
        return jdbc.query(COLUMNS + " WHERE c.id = ?", rowMapper, id).stream().findFirst();
    }

    /**
     * 供 {@code adminAudit} 日志行使用：只记一个 id 算不上审计线索，必须能落到具体用户。
     */
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
