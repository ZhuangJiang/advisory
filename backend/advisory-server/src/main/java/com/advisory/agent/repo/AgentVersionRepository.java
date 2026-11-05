package com.advisory.agent.repo;

import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.agent.domain.AgentVersion;
import com.advisory.common.jdbc.JdbcRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@code agent_versions} 的 JDBC 访问层。
 *
 * <p>只追加：{@link #insert} 是唯一的写操作。快照以 {@code jsonb} 存储，每次读取都重新解析成
 * {@link AgentSnapshot}，因此历史会话重放的是当初写进去的那份 JSON，绝不参考当前行。</p>
 */
@Repository
public class AgentVersionRepository extends JdbcRepository {

    private final RowMapper<AgentVersion> rowMapper = (rs, rowNum) -> new AgentVersion(
            rs.getLong("id"),
            rs.getLong("agent_id"),
            rs.getInt("version"),
            readSnapshot(rs.getString("snapshot")),
            rs.getTimestamp("created_at").toInstant());

    /** 追加一个版本（只追加，不更新）。 */
    public void insert(long agentId, int version, AgentSnapshot snapshot) {
        jdbc.update("INSERT INTO agent_versions (agent_id, version, snapshot) VALUES (?, ?, ?::jsonb)",
                agentId, version, toJson(snapshot));
    }

    /** 按版本号倒序返回历史版本。 */
    public List<AgentVersion> findByAgentIdOrderByVersionDesc(long agentId) {
        return jdbc.query("""
                SELECT id, agent_id, version, snapshot::text AS snapshot, created_at
                FROM agent_versions
                WHERE agent_id = ?
                ORDER BY version DESC
                """, rowMapper, agentId);
    }

    /** 读取指定版本的快照 JSON。 */
    public Optional<AgentSnapshot> findSnapshot(long agentId, int version) {
        return jdbc.query("""
                SELECT id, agent_id, version, snapshot::text AS snapshot, created_at
                FROM agent_versions
                WHERE agent_id = ? AND version = ?
                """, rowMapper, agentId, version)
                .stream()
                .map(AgentVersion::snapshot)
                .findFirst();
    }

    /** 统计版本数量。 */
    public long countByAgentId(long agentId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_versions WHERE agent_id = ?", Long.class, agentId);
        return count == null ? 0 : count;
    }

    private AgentSnapshot readSnapshot(String value) {
        return fromJson(value, AgentSnapshot.class, "agent_versions.snapshot");
    }
}
