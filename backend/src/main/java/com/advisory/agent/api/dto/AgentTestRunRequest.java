package com.advisory.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/agents/{id}/test} — one trial question against a given agent version.
 *
 * @param question the question to ask; the same 8000-character cap as a real message applies
 * @param version  the snapshot to run; {@code null} means the agent's current version. Anything
 *                 older is resolved from {@code agent_versions}, so a trial can reproduce what an
 *                 existing conversation would have answered before an edit.
 */
public record AgentTestRunRequest(@NotBlank String question, Integer version) {
}
