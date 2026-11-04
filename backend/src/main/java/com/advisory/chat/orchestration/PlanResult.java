package com.advisory.chat.orchestration;

import java.util.List;

/**
 * PLAN's structured output: {@code {"queries":[...]}} — 1–3 retrieval queries covering different
 * angles of the question. Null/blank entries are dropped and the list is clamped to three by the
 * orchestrator; an empty result falls back to the raw question.
 */
public record PlanResult(List<String> queries) {
}
