package com.advisory.chat.orchestration;

import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.util.List;

/**
 * Real-time observer of an orchestration run.
 *
 * <p>The orchestrator only calls these hooks; whether the events go to an SSE stream (Task 12), to a
 * test recorder or nowhere at all is the sink's business. Implementations must not throw: the
 * orchestration itself is the authoritative path and the persisted steps are the source of truth,
 * so a broken transport may lose the live view but never the answer (spec §9: "推理步骤是主数据，
 * SSE 只是实时通道").</p>
 */
public interface StepSink {

    /** A no-op sink for callers that do not care about progress (e.g. a batch re-run). */
    StepSink NOOP = new StepSink() {
        @Override
        public void onStepStart(int round, Phase phase) {
        }

        @Override
        public void onStepEnd(int round, Phase phase, String summary) {
        }

        @Override
        public void onDelta(Phase phase, String text) {
        }

        @Override
        public void onCitations(List<RetrievedChunk> chunks) {
        }
    };

    void onStepStart(int round, Phase phase);

    void onStepEnd(int round, Phase phase, String summary);

    /** Incremental text of PLAN / REFLECT / ANSWER; one call per step at this layer. */
    void onDelta(Phase phase, String text);

    /** The accumulated, deduplicated evidence after a retrieval round (newest order first seen). */
    void onCitations(List<RetrievedChunk> chunks);
}
