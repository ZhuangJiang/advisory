package com.advisory.chat.orchestration;

import com.advisory.agent.domain.AgentSnapshot;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.retrieval.RetrievalQuery;
import com.advisory.knowledge.retrieval.RetrievalService;
import com.advisory.knowledge.retrieval.RetrievedChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The fixed four-phase state machine: PLAN (once) → RETRIEVE → REFLECT → (loop) → ANSWER.
 *
 * <p>The loop follows spec §11 literally: PLAN runs once and produces 1–3 queries; every following
 * round re-enters at RETRIEVE with the queries REFLECT proposed, so a run with
 * {@code maxRounds = 3} is one PLAN, three RETRIEVE and three REFLECT steps plus ANSWER. The loop
 * ends when REFLECT reports {@code sufficient=true} or {@code round >= maxRounds}.</p>
 *
 * <p>Failure handling:</p>
 * <ul>
 *   <li><b>unparsable structured output</b> — treated as "insufficient"; two <em>consecutive</em>
 *       REFLECT parse failures jump to ANSWER with {@code truncated=true}. An unparsable PLAN falls
 *       back to the raw question and the run continues;</li>
 *   <li><b>step timeout (60s) / overall timeout (180s)</b> — straight to ANSWER with whatever
 *       evidence exists and {@code truncated=true}; the last REFLECT draft is the fallback when the
 *       final call cannot run;</li>
 *   <li><b>no evidence at all</b> — the model is never asked to answer and the fixed
 *       {@link #NO_EVIDENCE_ANSWER} is returned (spec §4: no answering from parametric knowledge).</li>
 * </ul>
 *
 * <p>Evidence accumulates across rounds and is deduplicated by {@code chunkId} in first-seen order,
 * so the {@code [n]} numbering in the ANSWER prompt matches {@code citations[n-1]} exactly. Every
 * phase writes a {@link ReasoningStepData} (RETRIEVE included) with latency and a JSON payload; the
 * {@link StepSink} is an observer only and may be a no-op.</p>
 *
 * <p>This class is intentionally not a Spring bean yet: the production {@link ChatModelGateway}
 * implementation arrives with the conversation layer (Task 12), and an un-wired bean would break
 * every integration test that boots the shared context.</p>
 */
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    /** Deterministic answer when retrieval produced nothing; never a model guess (spec §4). */
    public static final String NO_EVIDENCE_ANSWER = "知识库中没有找到与问题相关的依据。";

    static final int MAX_QUERIES = 3;
    static final int FINAL_TOP_K = 8;
    static final int HARD_MAX_ROUNDS = 6;
    private static final int MAX_CONSECUTIVE_PARSE_FAILURES = 2;
    private static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_OVERALL_TIMEOUT = Duration.ofSeconds(180);
    private static final String FAILURE_PAYLOAD = "{\"error\":\"unparsable\"}";
    private static final String TIMEOUT_PAYLOAD = "{\"error\":\"timeout\"}";

    private final RetrievalService retrievalService;
    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;
    private final Duration stepTimeout;
    private final Duration overallTimeout;
    private final ExecutorService executor;

    public ChatOrchestrator(RetrievalService retrievalService, ChatModelGateway gateway,
                            ObjectMapper objectMapper) {
        this(retrievalService, gateway, objectMapper, DEFAULT_STEP_TIMEOUT, DEFAULT_OVERALL_TIMEOUT);
    }

    public ChatOrchestrator(RetrievalService retrievalService, ChatModelGateway gateway,
                            ObjectMapper objectMapper, Duration stepTimeout, Duration overallTimeout) {
        this.retrievalService = Objects.requireNonNull(retrievalService, "retrievalService");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.stepTimeout = Objects.requireNonNull(stepTimeout, "stepTimeout");
        this.overallTimeout = Objects.requireNonNull(overallTimeout, "overallTimeout");
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "chat-orchestrator-step");
            thread.setDaemon(true);
            return thread;
        });
    }

    public OrchestrationResult run(AgentSnapshot agent, String question, StepSink sink) {
        Objects.requireNonNull(agent, "agent");
        if (question == null || question.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "question must not be blank");
        }
        StepSink events = sink == null ? StepSink.NOOP : sink;
        String trimmedQuestion = question.trim();
        int maxRounds = Math.min(Math.max(agent.maxRounds(), 1), HARD_MAX_ROUNDS);
        long deadlineNanos = System.nanoTime() + overallTimeout.toNanos();

        List<ReasoningStepData> steps = new ArrayList<>();
        LinkedHashMap<Long, RetrievedChunk> evidence = new LinkedHashMap<>();
        int consecutiveFailures = 0;
        boolean truncated = false;
        String draft = null;
        int round = 1;

        // ---------------------------------------------------------------- PLAN (once)
        events.onStepStart(round, Phase.PLAN);
        long planStartedAt = System.nanoTime();
        List<String> queries;
        boolean planParsed = true;
        try {
            PlanResult raw = timed(() -> gateway.chatStructured(agent.chatModelId(),
                    PromptTemplates.planSystem(), PromptTemplates.planUser(trimmedQuestion),
                    PlanResult.class));
            queries = normalizePlan(raw, List.of(trimmedQuestion)).queries();
        } catch (StepTimedOutException e) {
            steps.add(step(round, Phase.PLAN, "规划超时，按已有证据进入回答", TIMEOUT_PAYLOAD, planStartedAt));
            events.onStepEnd(round, Phase.PLAN, "超时");
            queries = List.of(trimmedQuestion);
            truncated = true;
        } catch (AppException e) {
            planParsed = false;
            queries = List.of(trimmedQuestion);
            steps.add(step(round, Phase.PLAN, "规划输出解析失败，直接用问题检索", FAILURE_PAYLOAD, planStartedAt));
            events.onStepEnd(round, Phase.PLAN, "解析失败");
            log.warn("PLAN output unparsable: {}", e.getMessage());
        }
        if (planParsed && !truncated) {
            PlanResult planned = new PlanResult(queries);
            String summary = "生成 " + queries.size() + " 条检索查询";
            steps.add(step(round, Phase.PLAN, summary, toJson(planned), planStartedAt));
            events.onStepEnd(round, Phase.PLAN, summary);
            events.onDelta(Phase.PLAN, toJson(planned));
        }

        // ---------------------------------------------------------------- RETRIEVE / REFLECT loop
        while (!truncated) {
            if (pastDeadline(deadlineNanos)) {
                truncated = true;
                break;
            }

            events.onStepStart(round, Phase.RETRIEVE);
            long startedAt = System.nanoTime();
            RetrievalQuery retrievalQuery = new RetrievalQuery(agent.kbIds(), agent.nodeScope(),
                    queries, agent.topK(), FINAL_TOP_K);
            List<RetrievedChunk> retrieved;
            try {
                retrieved = timed(() -> retrievalService.retrieve(retrievalQuery));
            } catch (StepTimedOutException e) {
                steps.add(step(round, Phase.RETRIEVE, "检索超时，按已有证据进入回答", TIMEOUT_PAYLOAD, startedAt));
                events.onStepEnd(round, Phase.RETRIEVE, "超时");
                truncated = true;
                break;
            }
            for (RetrievedChunk chunk : retrieved) {
                evidence.putIfAbsent(chunk.chunkId(), chunk);
            }
            String retrievalSummary = "召回 " + retrieved.size() + " 个片段（累计 " + evidence.size() + "）";
            steps.add(step(round, Phase.RETRIEVE, retrievalSummary, toJson(queries), startedAt));
            events.onStepEnd(round, Phase.RETRIEVE, retrievalSummary);
            events.onCitations(List.copyOf(evidence.values()));

            events.onStepStart(round, Phase.REFLECT);
            startedAt = System.nanoTime();
            boolean reflectParsed = true;
            ReflectResult reflect;
            final String draftSoFar = draft;
            final int reflectRound = round;
            try {
                ReflectResult raw = timed(() -> gateway.chatStructured(agent.chatModelId(),
                        PromptTemplates.reflectSystem(),
                        PromptTemplates.reflectUser(trimmedQuestion,
                                PromptTemplates.numberedEvidence(List.copyOf(evidence.values())),
                                draftSoFar, reflectRound, maxRounds),
                        ReflectResult.class));
                reflect = normalizeReflect(raw);
                consecutiveFailures = 0;
            } catch (StepTimedOutException e) {
                steps.add(step(round, Phase.REFLECT, "反思超时，按已有证据进入回答", TIMEOUT_PAYLOAD, startedAt));
                events.onStepEnd(round, Phase.REFLECT, "超时");
                truncated = true;
                break;
            } catch (AppException e) {
                consecutiveFailures++;
                reflectParsed = false;
                reflect = new ReflectResult(false, List.of(), List.of(), draftSoFar);
                steps.add(step(round, Phase.REFLECT, "反思输出解析失败，按证据不足处理", FAILURE_PAYLOAD, startedAt));
                events.onStepEnd(round, Phase.REFLECT, "解析失败");
                log.warn("REFLECT output unparsable on round {}: {}", round, e.getMessage());
                if (consecutiveFailures >= MAX_CONSECUTIVE_PARSE_FAILURES) {
                    truncated = true;
                    break;
                }
            }
            draft = firstNonBlank(reflect.draftAnswer(), draft);
            if (reflectParsed) {
                String reflectSummary = reflect.sufficient()
                        ? "证据充分"
                        : "证据不足（缺口 " + reflect.gaps().size() + " 项）";
                steps.add(step(round, Phase.REFLECT, reflectSummary, toJson(reflect), startedAt));
                events.onStepEnd(round, Phase.REFLECT, reflectSummary);
                events.onDelta(Phase.REFLECT, toJson(reflect));
            }

            if (reflect.sufficient() || round >= maxRounds) {
                break;
            }
            queries = nextQueries(reflect, queries);
            round++;
        }

        // ---------------------------------------------------------------- ANSWER
        events.onStepStart(round, Phase.ANSWER);
        long startedAt = System.nanoTime();
        List<RetrievedChunk> citations = List.copyOf(evidence.values());
        String answer;
        if (citations.isEmpty()) {
            answer = NO_EVIDENCE_ANSWER;
            steps.add(step(round, Phase.ANSWER, "没有可引用片段，直接回复未找到", "{\"citations\":0}", startedAt));
            events.onStepEnd(round, Phase.ANSWER, "无引用片段");
        } else if (pastDeadline(deadlineNanos)) {
            truncated = true;
            answer = firstNonBlank(draft, NO_EVIDENCE_ANSWER);
            steps.add(step(round, Phase.ANSWER, "整体超时，返回最后一轮草稿", TIMEOUT_PAYLOAD, startedAt));
            events.onStepEnd(round, Phase.ANSWER, "整体超时");
        } else {
            String numbered = PromptTemplates.numberedEvidence(citations);
            try {
                answer = timed(() -> gateway.chat(agent.chatModelId(),
                        PromptTemplates.answerSystem(),
                        PromptTemplates.answerUser(trimmedQuestion, numbered)));
                steps.add(step(round, Phase.ANSWER, "基于 " + citations.size() + " 个片段生成答案",
                        "{\"citations\":" + citations.size() + "}", startedAt));
                events.onStepEnd(round, Phase.ANSWER, "已生成");
            } catch (StepTimedOutException e) {
                truncated = true;
                answer = firstNonBlank(draft, NO_EVIDENCE_ANSWER);
                steps.add(step(round, Phase.ANSWER, "终答超时，返回最后一轮草稿", TIMEOUT_PAYLOAD, startedAt));
                events.onStepEnd(round, Phase.ANSWER, "超时");
            }
        }
        if (answer == null || answer.isBlank()) {
            answer = firstNonBlank(draft, NO_EVIDENCE_ANSWER);
        }
        events.onDelta(Phase.ANSWER, answer);

        return new OrchestrationResult(answer, steps, citations, truncated, TokenUsage.ZERO);
    }

    // ------------------------------------------------------------------ step execution

    /** Runs one model/retrieval call under the per-step timeout and rethrows its own failures. */
    private <T> T timed(Supplier<T> body) {
        Future<T> future = executor.submit(body::get);
        try {
            return future.get(stepTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StepTimedOutException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new StepTimedOutException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Orchestration step failed", cause);
        }
    }

    // ------------------------------------------------------------------ normalization

    private static PlanResult normalizePlan(PlanResult raw, List<String> fallback) {
        List<String> queries = clean(raw == null ? null : raw.queries(), MAX_QUERIES);
        return new PlanResult(queries.isEmpty() ? List.copyOf(fallback) : queries);
    }

    private static ReflectResult normalizeReflect(ReflectResult raw) {
        if (raw == null) {
            return new ReflectResult(false, List.of(), List.of(), null);
        }
        return new ReflectResult(raw.sufficient(),
                clean(raw.gaps(), Integer.MAX_VALUE),
                clean(raw.nextQueries(), MAX_QUERIES),
                trimToNull(raw.draftAnswer()));
    }

    /** Next round's queries: REFLECT's own queries, else its gaps, else re-use the current ones. */
    private static List<String> nextQueries(ReflectResult reflect, List<String> previous) {
        if (!reflect.nextQueries().isEmpty()) {
            return reflect.nextQueries();
        }
        if (!reflect.gaps().isEmpty()) {
            return reflect.gaps();
        }
        return previous;
    }

    private static List<String> clean(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (unique.size() >= limit) {
                break;
            }
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                unique.add(trimmed);
            }
        }
        return List.copyOf(unique);
    }

    // ------------------------------------------------------------------ small helpers

    private static boolean pastDeadline(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos > 0;
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ReasoningStepData step(int round, Phase phase, String summary, String payload,
                                   long startedAtNanos) {
        long latencyMs = Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
        return new ReasoningStepData(round, phase, summary, payload, latencyMs);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}"; // a logging payload must never fail a question
        }
    }

    /** Internal signal: not a business error, never escapes {@link #run}. */
    private static final class StepTimedOutException extends RuntimeException {
        private StepTimedOutException() {
            super("orchestration step timed out", null, false, false);
        }
    }
}
