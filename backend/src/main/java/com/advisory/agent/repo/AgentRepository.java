package com.advisory.agent.repo;

import com.advisory.agent.domain.Agent;
import com.advisory.agent.domain.AgentSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC access for {@code agents}.
 *
 * <p>The module writes with JDBC rather than JPA: {@code kb_ids} / {@code node_scope} are
 * {@code jsonb} and every read here follows a write in the same request, so a JPA persistence
 * context would hand back stale entities (the same class of bug fixed for the node tree in Task 4).
 * The JSON columns are selected as {@code ::text} and mapped through Jackson here, so the service
 * layer only ever sees typed values.</p>
 */
@Repository
public class AgentRepository {

    private static final String COLUMNS = """
            id, name, description, system_prompt, chat_model_id, embedding_model_id,
            kb_ids::text AS kb_ids, node_scope::text AS node_scope, max_rounds, top_k,
            temperature, enabled, current_version, created_at, updated_at
            """;

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<Long, List<Long>>> NODE_SCOPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Agent> rowMapper = (rs, rowNum) -> new Agent(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("system_prompt"),
            rs.getObject("chat_model_id", Long.class),
            rs.getObject("embedding_model_id", Long.class),
            readLongList(rs.getString("kb_ids")),
            readNodeScope(rs.getString("node_scope")),
            rs.getInt("max_rounds"),
            rs.getInt("top_k"),
            rs.getBigDecimal("temperature"),
            rs.getBoolean("enabled"),
            rs.getInt("current_version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public AgentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Agent> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agents WHERE id = ?", rowMapper, id)
                .stream()
                .findFirst();
    }

    /**
     * Locks the row for the duration of the transaction. Every version bump goes through this read
     * so two concurrent PATCHes serialize instead of both writing {@code current_version + 1} and
     * losing one version (the (agent_id, version) unique index would otherwise fail the request).
     */
    public Optional<Agent> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agents WHERE id = ? FOR UPDATE", rowMapper, id)
                .stream()
                .findFirst();
    }

    public List<Agent> findPage(int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agents ORDER BY id LIMIT ? OFFSET ?",
                rowMapper, limit, offset);
    }

    public long count() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM agents", Long.class);
        return total == null ? 0 : total;
    }

    public boolean existsByName(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    public boolean existsByNameExcluding(String name, long excludedId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE name = ? AND id <> ?", Integer.class, name, excludedId);
        return count != null && count > 0;
    }

    /** Inserts the live row at {@code current_version = 1} and returns the generated id. */
    public long insert(AgentSnapshot snapshot, String description, boolean enabled) {
        return jdbc.queryForObject("""
                INSERT INTO agents (name, description, system_prompt, chat_model_id, embedding_model_id,
                                    kb_ids, node_scope, max_rounds, top_k, temperature, enabled,
                                    current_version)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, 1)
                RETURNING id
                """, Long.class,
                snapshot.name(),
                description,
                snapshot.systemPrompt(),
                snapshot.chatModelId(),
                snapshot.embeddingModelId(),
                write(snapshot.kbIds()),
                write(snapshot.nodeScope()),
                snapshot.maxRounds(),
                snapshot.topK(),
                snapshot.temperature(),
                enabled);
    }

    /** Overwrites the live configuration and bumps {@code current_version}/{@code updated_at}. */
    public void update(long id, AgentSnapshot snapshot, String description, boolean enabled,
                       int newVersion) {
        jdbc.update("""
                UPDATE agents
                SET name = ?, description = ?, system_prompt = ?, chat_model_id = ?,
                    embedding_model_id = ?, kb_ids = ?::jsonb, node_scope = ?::jsonb,
                    max_rounds = ?, top_k = ?, temperature = ?, enabled = ?,
                    current_version = ?, updated_at = now()
                WHERE id = ?
                """,
                snapshot.name(),
                description,
                snapshot.systemPrompt(),
                snapshot.chatModelId(),
                snapshot.embeddingModelId(),
                write(snapshot.kbIds()),
                write(snapshot.nodeScope()),
                snapshot.maxRounds(),
                snapshot.topK(),
                snapshot.temperature(),
                enabled,
                newVersion,
                id);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM agents WHERE id = ?", id);
    }

    public long countConversations(long agentId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE agent_id = ?", Long.class, agentId);
        return count == null ? 0 : count;
    }

    /** The ids and embedding models of the given knowledge bases; missing rows are simply absent. */
    public List<KbEmbedding> findKbEmbeddings(List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(kbIds.size(), "?"));
        return jdbc.query(
                "SELECT id, embedding_model_id FROM knowledge_bases WHERE id IN (" + placeholders + ")",
                (rs, rowNum) -> new KbEmbedding(rs.getLong("id"),
                        rs.getObject("embedding_model_id", Long.class)),
                kbIds.toArray());
    }

    /** The owning knowledge base of a node, or empty when the node does not exist. */
    public Optional<Long> findNodeKbId(long nodeId) {
        return jdbc.query("SELECT kb_id FROM kb_nodes WHERE id = ?",
                        (rs, rowNum) -> rs.getLong("kb_id"), nodeId)
                .stream()
                .findFirst();
    }

    /** The capability of a model row, or empty when the model does not exist. */
    public Optional<String> findModelCapability(long modelId) {
        return jdbc.query("SELECT capability FROM models WHERE id = ?",
                        (rs, rowNum) -> rs.getString("capability"), modelId)
                .stream()
                .findFirst();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Agent JSON column cannot be serialized", e);
        }
    }

    private List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LONG_LIST);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("agents.kb_ids holds invalid JSON", e);
        }
    }

    private Map<Long, List<Long>> readNodeScope(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, NODE_SCOPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("agents.node_scope holds invalid JSON", e);
        }
    }

    /** A knowledge base id with its embedding model (nullable: the knowledge base may have none). */
    public record KbEmbedding(long kbId, Long embeddingModelId) {
    }
}
