package com.advisory.agent.repo;

import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.agent.domain.AgentVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JDBC access for {@code agent_versions}.
 *
 * <p>Append-only: {@link #insert} is the only write. The snapshot is stored as {@code jsonb} and
 * parsed back into {@link AgentSnapshot} on every read, so what a historical conversation replays is
 * exactly the JSON that was written — no live row is consulted.</p>
 */
@Repository
public class AgentVersionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<AgentVersion> rowMapper = (rs, rowNum) -> new AgentVersion(
            rs.getLong("id"),
            rs.getLong("agent_id"),
            rs.getInt("version"),
            readSnapshot(rs.getString("snapshot")),
            rs.getTimestamp("created_at").toInstant());

    public AgentVersionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(long agentId, int version, AgentSnapshot snapshot) {
        try {
            jdbc.update("INSERT INTO agent_versions (agent_id, version, snapshot) VALUES (?, ?, ?::jsonb)",
                    agentId, version, objectMapper.writeValueAsString(snapshot));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Agent snapshot cannot be serialized", e);
        }
    }

    public List<AgentVersion> findByAgentIdOrderByVersionDesc(long agentId) {
        return jdbc.query("""
                SELECT id, agent_id, version, snapshot::text AS snapshot, created_at
                FROM agent_versions
                WHERE agent_id = ?
                ORDER BY version DESC
                """, rowMapper, agentId);
    }

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

    public long countByAgentId(long agentId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_versions WHERE agent_id = ?", Long.class, agentId);
        return count == null ? 0 : count;
    }

    private AgentSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, AgentSnapshot.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("agent_versions.snapshot holds invalid JSON", e);
        }
    }
}
