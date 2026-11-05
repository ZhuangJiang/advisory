package com.advisory.chat.orchestration;

import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 编排运行的实时观察者。
 *
 * <p>编排器只负责回调这些钩子；事件是流向 SSE（Task 12）、流进测试记录器，还是干脆丢弃，都由实现自己决定。
 * 实现方必须保证不抛异常：编排本身才是主路径，落库的步骤才是权威数据，传输层出问题最多丢掉实时视图，
 * 绝不能丢掉答案（spec §9：「推理步骤是主数据，SSE 只是实时通道」）。</p>
 */
public interface StepSink {

    /** 空实现，供不关心进度的调用方使用（如批量重跑）。 */
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

    /** PLAN / REFLECT / ANSWER 的增量文本；在当前实现里每个步骤只回调一次，尚未做到逐 token。 */
    void onDelta(Phase phase, String text);

    /** 一轮检索后累积并去重过的证据，按首次出现的顺序排列。 */
    void onCitations(List<RetrievedChunk> chunks);
}
