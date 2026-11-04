package com.advisory.chat.orchestration;

import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.retrieval.RetrievalQuery;
import com.advisory.knowledge.retrieval.RetrievalService;
import com.advisory.knowledge.retrieval.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 11：四阶段编排状态机（PLAN → RETRIEVE → REFLECT → ANSWER）。
 *
 * <p>纯 JUnit，用脚本化的 {@link ChatModelGateway} 与 mock 掉的 {@link RetrievalService}：
 * 状态机本身不碰数据库、不启 Spring 上下文、不联网，因此每个分支（证据充分、始终不充分、
 * 结构化输出不可解析、证据为空、单步超时）都能确定性复现。</p>
 *
 * <p>编排器当时刻意没标 {@code @Service}：生产网关要到会话层（Task 12）才出现，提前注册一个依赖缺失的
 * bean 会让集成测试共享的应用上下文直接启动失败。</p>
 */
class ChatOrchestratorTest {

    private RetrievalService retrieval;
    private FakeGateway gateway;
    private RecordingSink sink;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        gateway = new FakeGateway();
        sink = new RecordingSink();
        orchestrator = new ChatOrchestrator(retrieval, gateway, new ObjectMapper());
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk(1)));
    }

    // ------------------------------------------------------------------ 1. 第一轮即判定证据充分

    @Test
    void stopsWhenReflectSaysSufficient() {
        gateway.plans.add(new PlanResult(List.of("query one", "query two")));
        gateway.reflects.add(new ReflectResult(true, List.of(), List.of(), "draft answer"));
        gateway.answers.add("最终答案[1]");

        OrchestrationResult result = orchestrator.run(agent(3, 20), "什么是向量检索？", sink);

        assertEquals("最终答案[1]", result.answer());
        assertFalse(result.truncated());
        assertEquals(List.of(Phase.PLAN, Phase.RETRIEVE, Phase.REFLECT, Phase.ANSWER),
                result.steps().stream().map(ReasoningStepData::phase).toList());
        assertEquals(1, result.citations().size());
        verify(retrieval, times(1)).retrieve(any());
        assertEquals(1, gateway.planCalls());

        // 接收器应恰好看到每个阶段的一次开始与一次结束，外加增量文本与引用列表
        assertEquals(List.of("start:PLAN", "end:PLAN", "delta:PLAN",
                        "start:RETRIEVE", "end:RETRIEVE",
                        "start:REFLECT", "end:REFLECT", "delta:REFLECT",
                        "start:ANSWER", "end:ANSWER", "delta:ANSWER"),
                sink.events);
        assertEquals(1, sink.citationBatches.size());
    }

    // ------------------------------------------------------------------ 2. 始终判定不充分

    @Test
    void runsToMaxRoundsWhenNeverSufficient() {
        gateway.plans.add(new PlanResult(List.of("q1")));
        gateway.plans.add(new PlanResult(List.of("q2")));
        gateway.plans.add(new PlanResult(List.of("q3")));
        for (int i = 1; i <= 3; i++) {
            gateway.reflects.add(new ReflectResult(false, List.of("gap-" + i), List.of("next-" + i),
                    "draft-" + i));
        }
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk(1)), List.of(chunk(2)), List.of(chunk(3)));

        OrchestrationResult result = orchestrator.run(agent(3, 20), "问题", sink);

        assertFalse(result.truncated(), "reaching maxRounds is not a truncation");
        assertEquals(3, result.citations().size(), "accumulated citations are deduplicated by chunkId");
        assertEquals(8, result.steps().size(), "PLAN once + 3 × (RETRIEVE, REFLECT) + ANSWER");
        verify(retrieval, times(3)).retrieve(any());
        assertEquals(1, gateway.planCalls(), "PLAN runs once; later rounds re-enter at RETRIEVE");
        assertEquals(3, gateway.reflectCalls());
        assertEquals(1, gateway.answerCalls());

        // 每一轮都用上一轮 REFLECT 提出的语句去检索
        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrieval, times(3)).retrieve(captor.capture());
        List<RetrievalQuery> queries = captor.getAllValues();
        assertEquals(List.of("q1"), queries.get(0).queries());
        assertEquals(List.of("next-1"), queries.get(1).queries());
        assertEquals(List.of("next-2"), queries.get(2).queries());
    }

    // ------------------------------------------------------------------ 3. 结构化输出不可解析

    @Test
    void illegalJsonDegradesInsteadOfThrowing() {
        gateway.plans.add(new PlanResult(List.of("q1")));
        gateway.reflects.add(new AppException(ErrorCode.VALIDATION_FAILED, "not json"));
        gateway.reflects.add(new AppException(ErrorCode.VALIDATION_FAILED, "not json"));

        OrchestrationResult result = orchestrator.run(agent(3, 20), "问题", sink);

        assertTrue(result.truncated(), "two consecutive REFLECT parse failures end the loop early");
        assertFalse(result.answer().isBlank(), "a degraded run must still return an answer");
        assertEquals(List.of(Phase.PLAN, Phase.RETRIEVE, Phase.REFLECT,
                        Phase.RETRIEVE, Phase.REFLECT, Phase.ANSWER),
                result.steps().stream().map(ReasoningStepData::phase).toList());
        assertTrue(result.steps().get(2).summary().contains("失败"));
        assertTrue(result.steps().get(4).summary().contains("失败"));
        // REFLECT 失败时提不出新语句，于是这一轮沿用上一轮的语句
        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrieval, times(2)).retrieve(captor.capture());
        assertEquals(List.of("q1"), captor.getAllValues().get(0).queries());
        assertEquals(List.of("q1"), captor.getAllValues().get(1).queries());
    }

    // ------------------------------------------------------------------ 4. 提示词与检索请求的形状

    @Test
    void answerPromptContainsNumberedCitations() {
        when(retrieval.retrieve(any())).thenReturn(List.of(
                new RetrievedChunk(1L, 100L, 10L, 11L, "handbook.pdf", "第一章", "向量检索使用余弦相似度。", 0.9),
                new RetrievedChunk(2L, 101L, 10L, 11L, "faq.md", "检索", "关键词检索使用 pg_trgm。", 0.8)));
        gateway.plans.add(new PlanResult(List.of("向量检索", "关键词检索", "余弦距离", "第四个查询会被裁掉")));
        gateway.reflects.add(new ReflectResult(true, List.of(), List.of(), "draft"));

        orchestrator.run(agent(3, 30), "检索是怎么做的？", sink);

        // ANSWER 的系统提示词必须钉死引用规则
        String system = gateway.answerSystemPrompts.get(0);
        assertTrue(system.contains("[1]"), "the answer prompt must show the [n] citation format");
        assertTrue(system.contains("知识库中没有找到"), "the answer prompt must forbid answering without evidence");

        // 用户提示词给每个片段编号，答案里的 [n] 才不会指向不明
        String user = gateway.answerUserPrompts.get(0);
        assertTrue(user.contains("[1] handbook.pdf"));
        assertTrue(user.contains("[2] faq.md"));
        assertTrue(user.contains("向量检索使用余弦相似度。"));
        assertTrue(user.contains("检索是怎么做的？"));

        // 检索请求要带上 Agent 的范围与 topK，融合后的上限固定为 8
        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrieval).retrieve(captor.capture());
        RetrievalQuery query = captor.getValue();
        assertEquals(List.of(10L), query.kbIds());
        assertEquals(Map.of(10L, List.of(11L)), query.nodeScope());
        assertEquals(30, query.perKbTopK());
        assertEquals(8, query.finalTopK());
        assertEquals(3, query.queries().size(), "PLAN output is clamped to at most three queries");
    }

    /**
     * Task 13 的修复：{@code agents.system_prompt} 过去只是存着、能版本化、能编辑，却从未发给模型——
     * 整个 Agent 自定义形同虚设。现在它会被拼到 ANSWER 指令之前；PLAN 与 REFLECT 仍用固定提示词，
     * 因为那两个阶段必须产出可解析的 JSON。
     */
    @Test
    void agentSystemPromptLeadsTheAnswerInstructions() {
        gateway.plans.add(new PlanResult(List.of("q1")));
        gateway.reflects.add(new ReflectResult(true, List.of(), List.of(), "draft"));

        orchestrator.run(agent(3, 20), "问题", sink);

        String system = gateway.answerSystemPrompts.get(0);
        assertTrue(system.startsWith("system prompt"),
                "the agent's own prompt must reach the model, not just the database");
        assertTrue(system.contains("[1]"), "the citation contract still applies after the agent prompt");
        assertTrue(system.contains("知识库中没有找到"), "the anti-hallucination rule is not overridable");
        assertFalse(gateway.planSystemPrompts.get(0).contains("system prompt"),
                "PLAN must keep its fixed JSON-only prompt");
    }

    // ------------------------------------------------------------------ 5. 完全没有证据

    @Test
    void emptyEvidenceSkipsTheAnswerCall() {
        when(retrieval.retrieve(any())).thenReturn(List.of());
        gateway.plans.add(new PlanResult(List.of("q1")));
        gateway.reflects.add(new ReflectResult(true, List.of(), List.of(), "draft"));

        OrchestrationResult result = orchestrator.run(agent(3, 20), "无人区的问题", sink);

        assertEquals(ChatOrchestrator.NO_EVIDENCE_ANSWER, result.answer());
        assertEquals(0, gateway.answerCalls(), "no snippets: the model is never asked to answer");
        assertTrue(result.citations().isEmpty());
        assertTrue(result.steps().stream().anyMatch(step -> step.phase() == Phase.ANSWER));
    }

    // ------------------------------------------------------------------ 6. 单步超时

    @Test
    void stepTimeoutEntersAnswerImmediately() {
        ChatOrchestrator impatient = new ChatOrchestrator(retrieval, gateway, new ObjectMapper(),
                Duration.ofMillis(50), Duration.ofSeconds(30));
        gateway.planDelayMillis = 2_000;

        long startedAt = System.nanoTime();
        OrchestrationResult result = impatient.run(agent(3, 20), "问题", sink);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(result.truncated());
        assertEquals(ChatOrchestrator.NO_EVIDENCE_ANSWER, result.answer());
        assertTrue(elapsedMillis < 5_000, "the step timeout must abort the call, took " + elapsedMillis + "ms");
        assertTrue(result.steps().get(0).summary().contains("超时"));
        verify(retrieval, never()).retrieve(any());
    }

    // ------------------------------------------------------------------ 测试夹具

    private static AgentSnapshot agent(int maxRounds, int topK) {
        return new AgentSnapshot("agent", "system prompt", 1L, 2L, List.of(10L),
                Map.of(10L, List.of(11L)), maxRounds, topK, new BigDecimal("0.20"));
    }

    private static RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, 100L + id, 10L, 11L, "doc-" + id, "node-" + id,
                "content-" + id, 0.5);
    }

    /**
     * 脚本化网关：每个队列里放返回值，或放待抛出的 {@link RuntimeException}；队列为空时返回一个无害的默认值，
     * 这样测试只需为它真正断言的部分写脚本。
     */
    private static final class FakeGateway implements ChatModelGateway {

        private final Deque<Object> plans = new ArrayDeque<>();
        private final Deque<Object> reflects = new ArrayDeque<>();
        private final Deque<Object> answers = new ArrayDeque<>();
        private final List<String> answerSystemPrompts = new ArrayList<>();
        private final List<String> answerUserPrompts = new ArrayList<>();
        private final List<String> planSystemPrompts = new ArrayList<>();
        private int planCalls;
        private int reflectCalls;
        private int answerCalls;
        private long planDelayMillis;

        @Override
        public String chat(Long modelId, String systemPrompt, String userPrompt) {
            answerCalls++;
            answerSystemPrompts.add(systemPrompt);
            answerUserPrompts.add(userPrompt);
            Object next = answers.isEmpty() ? "默认答案" : answers.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (String) next;
        }

        @Override
        public <T> T chatStructured(Long modelId, String systemPrompt, String userPrompt, Class<T> type) {
            if (type == PlanResult.class) {
                planCalls++;
                planSystemPrompts.add(systemPrompt);
                sleepIfAsked();
                return type.cast(next(plans, new PlanResult(List.of("default-query"))));
            }
            if (type == ReflectResult.class) {
                reflectCalls++;
                return type.cast(next(reflects,
                        new ReflectResult(true, List.of(), List.of(), "default-draft")));
            }
            throw new IllegalArgumentException("unexpected structured type: " + type);
        }

        private void sleepIfAsked() {
            if (planDelayMillis <= 0) {
                return;
            }
            try {
                Thread.sleep(planDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AppException(ErrorCode.INTERNAL_ERROR, "interrupted");
            }
        }

        private static Object next(Deque<Object> queue, Object fallback) {
            Object value = queue.isEmpty() ? fallback : queue.poll();
            if (value instanceof RuntimeException failure) {
                throw failure;
            }
            return value;
        }

        int planCalls() {
            return planCalls;
        }

        int reflectCalls() {
            return reflectCalls;
        }

        int answerCalls() {
            return answerCalls;
        }
    }

    private static final class RecordingSink implements StepSink {

        private final List<String> events = new ArrayList<>();
        private final List<List<RetrievedChunk>> citationBatches = new ArrayList<>();

        @Override
        public void onStepStart(int round, Phase phase) {
            events.add("start:" + phase);
        }

        @Override
        public void onStepEnd(int round, Phase phase, String summary) {
            events.add("end:" + phase);
        }

        @Override
        public void onDelta(Phase phase, String text) {
            events.add("delta:" + phase);
        }

        @Override
        public void onCitations(List<RetrievedChunk> chunks) {
            citationBatches.add(chunks);
        }
    }
}
