package com.advisory.chat.orchestration;

import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.retrieval.RetrievalService;
import com.advisory.knowledge.retrieval.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时工具分支：命中条件、成功即短路、失败回退到知识库检索。
 *
 * <p>这一层的价值全在「失败回退」上——MCP 是外部进程，它挂掉时天气问题必须退化成原来的
 * 「知识库检索」而不是会话失败。因此失败用例和成功用例一样重要。</p>
 */
class ChatOrchestratorToolTest {

    private RetrievalService retrieval;
    private ToolGateway gateway;
    private RecordingSink sink;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        gateway = new ToolGateway();
        sink = new RecordingSink();
        orchestrator = new ChatOrchestrator(retrieval, gateway, new ObjectMapper());
    }

    @Test
    void answersWeatherQuestionWithToolsAndSkipsRetrieval() {
        gateway.toolsAvailable = true;
        gateway.toolAnswer = "北京当前晴，气温 12.3 ℃。";

        OrchestrationResult result = orchestrator.run(agent(), "北京今天天气怎么样？", sink);

        assertEquals("北京当前晴，气温 12.3 ℃。", result.answer());
        assertTrue(result.citations().isEmpty(), "工具回答没有知识库引用");
        assertFalse(result.truncated());
        assertEquals(List.of(Phase.ANSWER), result.steps().stream().map(ReasoningStepData::phase).toList());
        assertEquals(1, gateway.toolCalls, "工具分支只调用一次模型");
        assertEquals(0, gateway.planCalls, "命中工具分支后不再做检索规划");
        verify(retrieval, never()).retrieve(any());
        assertEquals(List.of("start:ANSWER", "end:ANSWER", "delta:ANSWER"), sink.events);
    }

    @Test
    void toolFailureFallsBackToRetrieval() {
        gateway.toolsAvailable = true;
        gateway.toolFailure = new AppException(ErrorCode.INTERNAL_ERROR, "MCP 连接已断开");
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk(1)));
        gateway.plans.add(new PlanResult(List.of("q1")));
        gateway.reflects.add(new ReflectResult(true, List.of(), List.of(), "draft"));
        gateway.answers.add("知识库答案[1]");

        OrchestrationResult result = orchestrator.run(agent(), "北京今天天气怎么样？", sink);

        assertEquals("知识库答案[1]", result.answer());
        assertEquals(1, result.citations().size());
        assertEquals(1, gateway.toolCalls);
        assertEquals(1, gateway.planCalls, "回退后走完整流水线");
        verify(retrieval).retrieve(any());
    }

    @Test
    void toolTimeoutFallsBackToRetrieval() {
        gateway.toolsAvailable = true;
        gateway.toolDelayMillis = 5_000; // 编排器单步预算被测试压到 100ms
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk(1)));
        gateway.plans.add(new PlanResult(List.of("q1")));
        gateway.reflects.add(new ReflectResult(true, List.of(), List.of(), "draft"));
        gateway.answers.add("知识库答案[1]");

        OrchestrationResult result = new ChatOrchestrator(retrieval, gateway, new ObjectMapper(),
                java.time.Duration.ofMillis(100), java.time.Duration.ofSeconds(5))
                .run(agent(), "北京今天天气怎么样？", sink);

        assertEquals("知识库答案[1]", result.answer());
        assertEquals(1, gateway.toolCalls, "超时后不再等待工具");
    }

    @Test
    void nonWeatherQuestionNeverTouchesTools() {
        gateway.toolsAvailable = true;
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk(1)));
        gateway.plans.add(new PlanResult(List.of("q1")));
        gateway.reflects.add(new ReflectResult(true, List.of(), List.of(), "draft"));
        gateway.answers.add("知识库答案[1]");

        OrchestrationResult result = orchestrator.run(agent(), "碳排放核算边界怎么划定？", sink);

        assertEquals("知识库答案[1]", result.answer());
        assertEquals(0, gateway.toolCalls, "没有天气语义就不该走工具分支");
        assertEquals(1, gateway.planCalls);
    }

    @Test
    void weatherQuestionWithoutToolsKeepsOldBehaviour() {
        gateway.toolsAvailable = false;
        when(retrieval.retrieve(any())).thenReturn(List.of());
        gateway.plans.add(new PlanResult(List.of("q1")));

        OrchestrationResult result = orchestrator.run(agent(), "北京今天天气怎么样？", sink);

        assertEquals(ChatOrchestrator.NO_EVIDENCE_ANSWER, result.answer(),
                "工具不可用时，行为与接入前完全一致");
        assertEquals(0, gateway.toolCalls);
    }

    // ------------------------------------------------------------------ 夹具

    private static AgentSnapshot agent() {
        return new AgentSnapshot("碳资产助手", "system prompt", 1L, 2L, List.of(10L),
                Map.of(10L, List.of(11L)), 2, 10, new BigDecimal("0.20"));
    }

    private static RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, 100L + id, 10L, 11L, "doc-" + id, "node-" + id,
                "content-" + id, 0.5);
    }

    private static final class ToolGateway implements ChatModelGateway {

        private boolean toolsAvailable;
        private String toolAnswer = "默认工具答案";
        private RuntimeException toolFailure;
        private long toolDelayMillis;

        private final Deque<Object> plans = new ArrayDeque<>();
        private final Deque<Object> reflects = new ArrayDeque<>();
        private final Deque<Object> answers = new ArrayDeque<>();

        private int toolCalls;
        private int planCalls;
        private int answerCalls;

        @Override
        public boolean hasLiveTools() {
            return toolsAvailable;
        }

        @Override
        public String chatWithTools(Long modelId, BigDecimal temperature, String systemPrompt,
                                    String userPrompt) {
            toolCalls++;
            if (toolDelayMillis > 0) {
                try {
                    Thread.sleep(toolDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AppException(ErrorCode.INTERNAL_ERROR, "interrupted");
                }
            }
            if (toolFailure != null) {
                throw toolFailure;
            }
            return toolAnswer;
        }

        @Override
        public String chat(Long modelId, String systemPrompt, String userPrompt) {
            answerCalls++;
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
                return type.cast(next(plans, new PlanResult(List.of("default-query"))));
            }
            if (type == ReflectResult.class) {
                return type.cast(next(reflects,
                        new ReflectResult(true, List.of(), List.of(), "default-draft")));
            }
            throw new IllegalArgumentException("unexpected structured type: " + type);
        }

        private static Object next(Deque<Object> queue, Object fallback) {
            Object value = queue.isEmpty() ? fallback : queue.poll();
            if (value instanceof RuntimeException failure) {
                throw failure;
            }
            return value;
        }
    }

    private static final class RecordingSink implements StepSink {

        private final List<String> events = new ArrayList<>();

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
        }
    }
}
