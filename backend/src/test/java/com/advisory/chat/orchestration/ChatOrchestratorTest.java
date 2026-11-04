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
 * Task 11: the four-phase orchestration state machine (PLAN → RETRIEVE → REFLECT → ANSWER).
 *
 * <p>Plain JUnit with a scripted {@link ChatModelGateway} and a mocked {@link RetrievalService}:
 * the state machine has no database, no Spring context and no network, so every branch (sufficient,
 * never sufficient, malformed structured output, empty evidence, step timeout) is deterministic.
 * The orchestrator is deliberately not a {@code @Service} yet — the production gateway arrives with
 * the conversation layer (Task 12), and an un-wired bean would break the shared application
 * context of the integration tests.</p>
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

    // ------------------------------------------------------------------ 1. sufficient on round 1

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

        // the sink saw every phase start/end exactly once plus the deltas and the citations
        assertEquals(List.of("start:PLAN", "end:PLAN", "delta:PLAN",
                        "start:RETRIEVE", "end:RETRIEVE",
                        "start:REFLECT", "end:REFLECT", "delta:REFLECT",
                        "start:ANSWER", "end:ANSWER", "delta:ANSWER"),
                sink.events);
        assertEquals(1, sink.citationBatches.size());
    }

    // ------------------------------------------------------------------ 2. never sufficient

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

        // each round retrieves with the queries the previous REFLECT proposed
        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrieval, times(3)).retrieve(captor.capture());
        List<RetrievalQuery> queries = captor.getAllValues();
        assertEquals(List.of("q1"), queries.get(0).queries());
        assertEquals(List.of("next-1"), queries.get(1).queries());
        assertEquals(List.of("next-2"), queries.get(2).queries());
    }

    // ------------------------------------------------------------------ 3. malformed output

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
        // a failed REFLECT proposes nothing, so the round re-uses the previous queries
        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrieval, times(2)).retrieve(captor.capture());
        assertEquals(List.of("q1"), captor.getAllValues().get(0).queries());
        assertEquals(List.of("q1"), captor.getAllValues().get(1).queries());
    }

    // ------------------------------------------------------------------ 4. prompts and query shape

    @Test
    void answerPromptContainsNumberedCitations() {
        when(retrieval.retrieve(any())).thenReturn(List.of(
                new RetrievedChunk(1L, 100L, 10L, 11L, "handbook.pdf", "第一章", "向量检索使用余弦相似度。", 0.9),
                new RetrievedChunk(2L, 101L, 10L, 11L, "faq.md", "检索", "关键词检索使用 pg_trgm。", 0.8)));
        gateway.plans.add(new PlanResult(List.of("向量检索", "关键词检索", "余弦距离", "第四个查询会被裁掉")));
        gateway.reflects.add(new ReflectResult(true, List.of(), List.of(), "draft"));

        orchestrator.run(agent(3, 30), "检索是怎么做的？", sink);

        // the ANSWER system prompt pins the citation rules
        String system = gateway.answerSystemPrompts.get(0);
        assertTrue(system.contains("[1]"), "the answer prompt must show the [n] citation format");
        assertTrue(system.contains("知识库中没有找到"), "the answer prompt must forbid answering without evidence");

        // the user prompt numbers every snippet so [n] in the answer is unambiguous
        String user = gateway.answerUserPrompts.get(0);
        assertTrue(user.contains("[1] handbook.pdf"));
        assertTrue(user.contains("[2] faq.md"));
        assertTrue(user.contains("向量检索使用余弦相似度。"));
        assertTrue(user.contains("检索是怎么做的？"));

        // the retrieval request carries the agent's scope and topK, and the fused cap is 8
        ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrieval).retrieve(captor.capture());
        RetrievalQuery query = captor.getValue();
        assertEquals(List.of(10L), query.kbIds());
        assertEquals(Map.of(10L, List.of(11L)), query.nodeScope());
        assertEquals(30, query.perKbTopK());
        assertEquals(8, query.finalTopK());
        assertEquals(3, query.queries().size(), "PLAN output is clamped to at most three queries");
    }

    // ------------------------------------------------------------------ 5. no evidence

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

    // ------------------------------------------------------------------ 6. step timeout

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

    // ------------------------------------------------------------------ fixtures

    private static AgentSnapshot agent(int maxRounds, int topK) {
        return new AgentSnapshot("agent", "system prompt", 1L, 2L, List.of(10L),
                Map.of(10L, List.of(11L)), maxRounds, topK, new BigDecimal("0.20"));
    }

    private static RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, 100L + id, 10L, 11L, "doc-" + id, "node-" + id,
                "content-" + id, 0.5);
    }

    /**
     * Scripted gateway: each queue holds values or {@link RuntimeException}s to throw; an empty
     * queue answers with a benign default so a test only scripts what it asserts on.
     */
    private static final class FakeGateway implements ChatModelGateway {

        private final Deque<Object> plans = new ArrayDeque<>();
        private final Deque<Object> reflects = new ArrayDeque<>();
        private final Deque<Object> answers = new ArrayDeque<>();
        private final List<String> answerSystemPrompts = new ArrayList<>();
        private final List<String> answerUserPrompts = new ArrayList<>();
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
