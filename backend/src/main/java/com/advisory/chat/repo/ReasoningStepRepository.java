package com.advisory.chat.repo;

import com.advisory.chat.domain.ReasoningStep;
import com.advisory.chat.orchestration.Phase;
import com.advisory.chat.orchestration.ReasoningStepData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JDBC access for {@code reasoning_steps}.
 *
 * <p>Steps are written once the run finishes, from {@code OrchestrationResult.steps()} — the SSE
 * frames are the live view, this table is the master copy a reconnecting client reads back. A run
 * that dies mid-way therefore persists nothing partially invented: the message row keeps its
 * {@code FAILED}/{@code TRUNCATED} status and the steps that actually completed.</p>
 *
 * <p>{@code payload} is {@code {"summary": ..., "detail": "<step JSON as string>"}}: the table has
 * no summary column, and keeping {@code detail} a string means a payload that happens not to be
 * valid JSON can never fail the insert.</p>
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

    public List<ReasoningStep> findByMessage(long messageId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM reasoning_steps WHERE message_id = ? ORDER BY id",
                rowMapper, messageId);
    }
}
