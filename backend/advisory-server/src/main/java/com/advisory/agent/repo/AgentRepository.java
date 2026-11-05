package com.advisory.agent.repo;

import com.advisory.agent.domain.Agent;
import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.common.jdbc.JdbcRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code agents} 的 JDBC 访问层。
 *
 * <p>本模块用 JDBC 而不是 JPA 写入：{@code kb_ids} 与 {@code node_scope} 是 {@code jsonb}，且这里的读
 * 几乎总是紧跟在同一次请求的写之后，JPA 的一级缓存会把旧实体原样返回（Task 4 处理节点树时踩过同一类坑）。
 * JSON 列用 {@code ::text} 取出后在此处交给 Jackson 映射，服务层因此只接触强类型值。</p>
 */
@Repository
public class AgentRepository extends JdbcRepository {

    private static final String COLUMNS = """
            id, name, description, system_prompt, chat_model_id, embedding_model_id,
            kb_ids::text AS kb_ids, node_scope::text AS node_scope, max_rounds, top_k,
            temperature, enabled, current_version, created_at, updated_at
            """;

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<Long, List<Long>>> NODE_SCOPE = new TypeReference<>() {
    };

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

    /**
     * 按主键读取 Agent，含 jsonb 列的解析。
     *
     * <p>刻意不过滤 {@code deleted_at}：已软删 Agent 的历史会话仍要按锁定版本重放，
     * {@code resolveSnapshot} 必须能读到这一行。管理操作请用 {@link #findActiveById(long)}。</p>
     */
    public Optional<Agent> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agents WHERE id = ?", rowMapper, id)
                .stream()
                .findFirst();
    }

    /** 读取未删除的 Agent；管理操作与新建会话都走这里。 */
    public Optional<Agent> findActiveById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agents WHERE id = ? AND deleted_at IS NULL",
                        rowMapper, id)
                .stream()
                .findFirst();
    }

    /** 在事务期间锁住未删除的行，供状态切换与删除使用。 */
    public Optional<Agent> findActiveByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM agents WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                        rowMapper, id)
                .stream()
                .findFirst();
    }

    /**
     * 分页读取未删除的 Agent，按主键升序。
     *
     * <p>{@code enabled} 为 {@code null} 时不过滤在线状态（管理页要看到全部，包括已下线的）；
     * 非空时在 SQL 层过滤——过滤必须和分页一起做，否则调用方只能拿一页再在本地筛，
     * 一旦总量超过一页就会漏掉本该显示的在线 Agent。</p>
     *
     * @param limit   每页条数
     * @param offset  偏移量
     * @param enabled 是否只取在线（{@code true}）、只取下线（{@code false}）或不过滤（{@code null}）
     * @return 该页的 Agent
     */
    public List<Agent> findPage(int limit, int offset, Boolean enabled) {
        String sql = "SELECT " + COLUMNS + " FROM agents WHERE deleted_at IS NULL"
                + (enabled == null ? "" : " AND enabled = ?")
                + " ORDER BY id LIMIT ? OFFSET ?";
        return enabled == null
                ? jdbc.query(sql, rowMapper, limit, offset)
                : jdbc.query(sql, rowMapper, enabled, limit, offset);
    }

    /**
     * 统计未删除的 Agent 总数，供分页使用；{@code enabled} 的含义与 {@link #findPage} 一致。
     *
     * @param enabled 是否按在线状态过滤，{@code null} 表示不限制
     * @return 满足条件的总数
     */
    public long count(Boolean enabled) {
        Long total = enabled == null
                ? jdbc.queryForObject(
                        "SELECT count(*) FROM agents WHERE deleted_at IS NULL", Long.class)
                : jdbc.queryForObject(
                        "SELECT count(*) FROM agents WHERE deleted_at IS NULL AND enabled = ?",
                        Long.class, enabled);
        return total == null ? 0 : total;
    }

    /** 判断名称是否已被未删除的 Agent 占用。 */
    public boolean existsByName(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE name = ? AND deleted_at IS NULL",
                Integer.class, name);
        return count != null && count > 0;
    }

    /** 判断名称是否被其他未删除的 Agent 占用，更新时使用。 */
    public boolean existsByNameExcluding(String name, long excludedId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE name = ? AND id <> ? AND deleted_at IS NULL",
                Integer.class, name, excludedId);
        return count != null && count > 0;
    }

    /**
     * 插入当前行并把 {@code current_version} 置为 1。
     *
     * @param agent 待插入的 Agent
     * @return 数据库生成的主键
     */
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
                toJson(snapshot.kbIds()),
                toJson(snapshot.nodeScope()),
                snapshot.maxRounds(),
                snapshot.topK(),
                snapshot.temperature(),
                enabled);
    }

    /**
     * 覆盖当前配置并递增 {@code current_version}、刷新 {@code updated_at}。
     *
     * @param agent 新的配置，{@code currentVersion} 已是递增后的值
     */
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
                toJson(snapshot.kbIds()),
                toJson(snapshot.nodeScope()),
                snapshot.maxRounds(),
                snapshot.topK(),
                snapshot.temperature(),
                enabled,
                newVersion,
                id);
    }

    /** 只切换在线状态并刷新时间，不触碰版本号。 */
    public void updateEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE agents SET enabled = ?, updated_at = now()"
                + " WHERE id = ? AND deleted_at IS NULL", enabled, id);
    }

    /** 软删：只写 deleted_at，保留会话、消息、版本与历史配置。 */
    public void softDelete(long id) {
        jdbc.update("UPDATE agents SET deleted_at = now()"
                + " WHERE id = ? AND deleted_at IS NULL", id);
    }

    /**
     * 批量读取知识库的向量模型；不存在的 id 直接不出现在结果里，由调用方比对数量来判断是否缺失。
     *
     * @param kbIds 知识库 id 列表
     * @return id 到向量模型 id 的映射（模型可能为空）
     */
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

    /**
     * 查询节点所属的知识库，用于校验 {@code nodeScope} 的归属关系。
     *
     * @param nodeId 节点 id
     * @return 所属知识库 id；节点不存在时为空
     */
    public Optional<Long> findNodeKbId(long nodeId) {
        return jdbc.query("SELECT kb_id FROM kb_nodes WHERE id = ?",
                        (rs, rowNum) -> rs.getLong("kb_id"), nodeId)
                .stream()
                .findFirst();
    }

    /**
     * 查询模型的能力类型，用于确认对话模型确实可用于对话。
     *
     * @param modelId 模型 id
     * @return 能力类型；模型不存在时为空
     */
    public Optional<String> findModelCapability(long modelId) {
        return jdbc.query("SELECT capability FROM models WHERE id = ?",
                        (rs, rowNum) -> rs.getString("capability"), modelId)
                .stream()
                .findFirst();
    }

    private List<Long> readLongList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return fromJson(value, LONG_LIST, "agents.kb_ids");
    }

    private Map<Long, List<Long>> readNodeScope(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return fromJson(value, NODE_SCOPE, "agents.node_scope");
    }

    /**
     * 知识库与其向量模型的配对。
     *
     * @param kbId               知识库 id
     * @param embeddingModelId   向量模型 id，可能为空（知识库尚未指定模型）
     */
    public record KbEmbedding(long kbId, Long embeddingModelId) {
    }
}
