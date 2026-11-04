package com.advisory.chat.repo;

import com.advisory.chat.domain.ReasoningStep;
import com.advisory.chat.orchestration.Phase;
import com.advisory.chat.orchestration.ReasoningStepData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@code reasoning_steps} 的 JDBC 访问层。
 *
 * <p>步骤在运行结束后依据 {@code OrchestrationResult.steps()} 一次性写入——SSE 帧只是实时视图，
 * 这张表才是断线客户端回读的主副本。因此中途崩掉的运行不会留下半截编造的内容：
 * 消息行保留 {@code FAILED}/{@code TRUNCATED} 状态，步骤只保存真正完成的部分。</p>
 *
 * <p>{@code payload} 形如 {@code {"summary": ..., "detail": "<步骤 JSON 字符串>"}}：表里没有摘要列，
 * 而把 {@code detail} 保持为字符串，则保证内容恰好不是合法 JSON 时也不会导致插入失败。</p>
 */
@Repository
public class ReasoningStepRepository {

    private static final String COLUMNS = """
            id, message_id, round, phase, payload ->> 'summary' AS summary,
            payload ->> 'detail' AS detail, COALESCE(latency_ms, 0) AS latency_ms, created_at
            """;

    private final JdbcTemplate jdbc;

    private final RowMapper<ReasoningStep> rowMapper = (rs, rowNum) -> new ReasoningStep(
            rs.getLong("id"),
            rs.getLong("message_id"),
            rs.getInt("round"),
            Phase.valueOf(rs.getString("phase")),
            rs.getString("summary"),
            rs.getString("detail"),
            rs.getLong("latency_ms"),
            rs.getTimestamp("created_at").toInstant());

    public ReasoningStepRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量写入一次运行的全部步骤。 */
    public void insertAll(long messageId, List<ReasoningStepData> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO reasoning_steps (message_id, round, phase, payload, latency_ms)
                VALUES (?, ?, ?, jsonb_build_object('summary', ?, 'detail', ?), ?)
                """, steps, steps.size(), (ps, step) -> {
            ps.setLong(1, messageId);
            ps.setInt(2, step.round());
            ps.setString(3, step.phase().name());
            ps.setString(4, step.summary());
            ps.setString(5, step.payload());
            ps.setLong(6, step.latencyMs());
        });
    }

    /** 读取某条消息的步骤，按执行顺序。 */
    public List<ReasoningStep> findByMessage(long messageId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM reasoning_steps WHERE message_id = ? ORDER BY id",
                rowMapper, messageId);
    }
}
