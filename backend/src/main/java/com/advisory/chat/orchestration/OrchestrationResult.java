package com.advisory.chat.orchestration;

import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.util.List;

/**
 * The outcome of one question-answering run.
 *
 * @param answer    the final Markdown answer (never null/blank)
 * @param steps     every executed step in order; the persisted copy is the source of truth
 * @param citations the accumulated, deduplicated evidence, numbered by position in the ANSWER prompt
 * @param truncated true when the run stopped early (step/overall timeout, or two consecutive
 *                  unparsable structured answers) — the message row is stored as {@code TRUNCATED}
 * @param usage     token usage, zero when the gateway does not report it
 */
public record OrchestrationResult(String answer, List<ReasoningStepData> steps,
                                  List<RetrievedChunk> citations, boolean truncated, TokenUsage usage) {

    public OrchestrationResult {
        steps = List.copyOf(steps);
        citations = List.copyOf(citations);
    }
}
