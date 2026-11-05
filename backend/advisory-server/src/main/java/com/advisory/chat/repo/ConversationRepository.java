package com.advisory.chat.repo;

import com.advisory.chat.domain.Conversation;
import com.advisory.common.jdbc.JdbcRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@code conversations} 的 JDBC 访问层。
 *
 * <p>每次读取都顺带关联出 {@code agent_name}：会话列表是唯一需要它的地方，放在这里可以避免服务层再逐个查一次。
 * 除管理员外，读取一律按 {@code user_id} 过滤——服务层用 {@code null} 表示「不过滤」，SQL 依此分支，
 * 这样即便某处忘了传过滤条件，也只会多返回而不是泄露他人会话。</p>
 */
@Repository
public class ConversationRepository extends JdbcRepository {

    private static final String COLUMNS = """
            c.id, c.agent_id, a.name AS agent_name, c.agent_version, c.user_id, c.title,
            c.created_at, c.updated_at
            """;


    private final RowMapper<Conversation> rowMapper = (rs, rowNum) -> new Conversation(
            rs.getLong("id"),
            rs.getLong("agent_id"),
            rs.getString("agent_name"),
            rs.getInt("agent_version"),
            rs.getLong("user_id"),
            rs.getString("title"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());


    /** 新建会话并锁定 Agent 版本。 */
    public long insert(long agentId, int agentVersion, long userId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO conversations (agent_id, agent_version, user_id, title)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, agentId, agentVersion, userId, title);
    }

    /** 按主键读取会话，关联出 Agent 名称。 */
    public Optional<Conversation> findById(long id) {
        return jdbc.query("""
                SELECT %s FROM conversations c JOIN agents a ON a.id = c.agent_id WHERE c.id = ?
                """.formatted(COLUMNS), rowMapper, id).stream().findFirst();
    }

    /** {@code userId} 为 {@code null} 表示「所有用户」（管理员），否则严格限定为该用户。 */
    public List<Conversation> findPage(Long userId, int limit, int offset) {
        String sql = """
                SELECT %s FROM conversations c JOIN agents a ON a.id = c.agent_id
                WHERE (CAST(? AS BIGINT) IS NULL OR c.user_id = CAST(? AS BIGINT))
                ORDER BY c.created_at DESC, c.id DESC
                LIMIT ? OFFSET ?
                """.formatted(COLUMNS);
        return jdbc.query(sql, rowMapper, userId, userId, limit, offset);
    }

    /** 统计会话数，供分页使用。 */
    public long count(Long userId) {
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM conversations
                WHERE (CAST(? AS BIGINT) IS NULL OR user_id = CAST(? AS BIGINT))
                """, Long.class, userId, userId);
        return total == null ? 0 : total;
    }

    /** 问题完成后刷新 {@code updated_at}，使列表能按最近活跃排序。 */
    public void touch(long id) {
        jdbc.update("UPDATE conversations SET updated_at = now() WHERE id = ?", id);
    }

    /**
     * 只在会话还没有标题时写入标题，返回是否真的写了。
     *
     * <p>{@code title IS NULL OR btrim(title) = ''} 是这条语句的全部意义：命名发生在提问时，
     * 而一个会话可以被问很多次，后到的问题绝不能把标题改掉。</p>
     */
    public boolean renameIfUntitled(long id, String title) {
        return jdbc.update("""
                UPDATE conversations SET title = ?
                WHERE id = ? AND (title IS NULL OR btrim(title) = '')
                """, title, id) > 0;
    }

    /**
     * 删除会话。消息与推理步骤由数据库级联清理（{@code ON DELETE CASCADE}），
     * 因此这里一次 {@code DELETE} 就够了，不要在应用层逐表删——漏一张就是外键报错。
     */
    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM conversations WHERE id = ?", id) > 0;
    }
}
