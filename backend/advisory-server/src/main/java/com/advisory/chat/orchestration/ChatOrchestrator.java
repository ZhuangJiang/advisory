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
 * 固定的四阶段状态机：PLAN（一次）→ RETRIEVE → REFLECT →（循环）→ ANSWER。
 *
 * <p>循环严格按 spec §11 实现：PLAN 只跑一次并产出 1–3 条检索语句；之后每一轮都从这里提出的语句重新进入
 * RETRIEVE，因此 {@code maxRounds = 3} 意味着 1 次 PLAN、3 次 RETRIEVE、3 次 REFLECT，再加 1 次 ANSWER。
 * 当 REFLECT 报告 {@code sufficient=true} 或 {@code round >= maxRounds} 时结束循环。</p>
 *
 * <p><b>两个短路</b>，都是为了不让「没有知识库依据」的问题假装跑完一条完整流水线：</p>
 * <ul>
 *   <li><b>寒暄</b>（{@link SmallTalk}）——「你好」「谢谢」「你是谁」不检索、不调模型，直接回一句固定话术。
 *       否则向量检索一定会凑出 8 个「最像」的无关片段，REFLECT 只能判不足、再补一轮，最后基于噪声写出一篇
 *       像模像样的答案；</li>
 *   <li><b>零召回 / 零新增</b>——某一轮一条都没召回到，或者召回到的全是已有片段，循环立即收敛到 ANSWER。
 *       REFLECT 判的是「这批片段够不够」，没有新片段可判时它只能对着「（无）」再编一轮查询。</li>
 * </ul>
 *
 * <p>失败处理：</p>
 * <ul>
 *   <li><b>结构化输出不可解析</b>——视为「证据不足」；REFLECT 连续两次解析失败则直接跳转 ANSWER 并标记
 *       {@code truncated=true}。PLAN 解析失败则退回用户原始问题，流程继续；</li>
 *   <li><b>单步超时（60 秒）或整体超时（180 秒）</b>——带着已有证据直接进入 ANSWER，标记
 *       {@code truncated=true}；若最后一次模型调用来不及，就用上一轮 REFLECT 的草稿兜底；</li>
 *   <li><b>完全没有证据</b>——不再调用模型作答，直接返回固定的 {@link #NO_EVIDENCE_ANSWER}
 *       （spec §4：不得凭参数化知识回答）；</li>
 *   <li><b>提示词超出模型窗口</b>——证据按窗口预算裁剪；裁剪后仍超出时，REFLECT 直接结束循环，
 *       ANSWER 返回 {@link #CONTEXT_EXCEEDED_ANSWER} 并以 {@code truncated} 收尾，而不是让整条会话
 *       {@code FAILED}。本地小模型（默认 4096 窗口）全靠这一点才不至于一问就崩。</li>
 * </ul>
 *
 * <p>证据跨轮累积，并按 {@code chunkId} 以首次出现顺序去重，因此 ANSWER 提示词里的 {@code [n]} 与
 * {@code citations[n-1]} 严格对应。每个阶段（含 RETRIEVE）都会写入一条带耗时与 JSON 载荷的
 * {@link ReasoningStepData}；{@link StepSink} 只是观察者，允许是空实现。</p>
 *
 * <p>由 {@code ChatConfig} 配合生产版 {@link ChatModelGateway} 注册为 bean；本类自身不依赖框架
 * （无注解、无 Spring 类型），单元测试才能用脚本化假实现驱动它。{@link #shutdown()} 在上下文关闭时
 * 停掉自己的步骤执行器。</p>
 */
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    /** 检索结果为空时的固定答复；绝不让模型凭空作答（spec §4）。 */
    public static final String NO_EVIDENCE_ANSWER = "知识库中没有找到与问题相关的依据。";

    /**
     * 证据已经召回、但提示词仍超出模型窗口时的答复。
     *
     * <p>这不是「没找到」：知识库里可能有依据，只是当前模型的上下文装不下。把两者混淆会让用户以为
     * 资料不存在，因此单独给一条可操作的提示，并以 {@code TRUNCATED} 结束而不是 {@code FAILED}。</p>
     */
    public static final String CONTEXT_EXCEEDED_ANSWER =
            "检索到的资料超出当前模型可用的上下文长度，无法生成完整答案。"
                    + "请换用上下文更大的模型，或缩小知识库与检索范围后重试。";

    static final int MAX_QUERIES = 3;
    static final int FINAL_TOP_K = 8;
    static final int HARD_MAX_ROUNDS = 6;
    private static final int MAX_CONSECUTIVE_PARSE_FAILURES = 2;
    private static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_OVERALL_TIMEOUT = Duration.ofSeconds(180);
    private static final String FAILURE_PAYLOAD = "{\"error\":\"unparsable\"}";
    private static final String TIMEOUT_PAYLOAD = "{\"error\":\"timeout\"}";
    private static final String CONTEXT_PAYLOAD = "{\"error\":\"context_exceeded\"}";

    private final RetrievalService retrievalService;
    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;
    private final Duration stepTimeout;
    private final Duration overallTimeout;
    private final int contextTokens;
    private final ExecutorService executor;

    public ChatOrchestrator(RetrievalService retrievalService, ChatModelGateway gateway,
                            ObjectMapper objectMapper) {
        this(retrievalService, gateway, objectMapper, DEFAULT_STEP_TIMEOUT, DEFAULT_OVERALL_TIMEOUT,
                PromptBudget.DEFAULT_CONTEXT_TOKENS);
    }

    public ChatOrchestrator(RetrievalService retrievalService, ChatModelGateway gateway,
                            ObjectMapper objectMapper, Duration stepTimeout, Duration overallTimeout) {
        this(retrievalService, gateway, objectMapper, stepTimeout, overallTimeout,
                PromptBudget.DEFAULT_CONTEXT_TOKENS);
    }

    /**
     * @param contextTokens 模型上下文窗口，用于裁剪提示词里的证据；非正数时取默认值
     */
    public ChatOrchestrator(RetrievalService retrievalService, ChatModelGateway gateway,
                            ObjectMapper objectMapper, Duration stepTimeout, Duration overallTimeout,
                            int contextTokens) {
        this.retrievalService = Objects.requireNonNull(retrievalService, "retrievalService");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.stepTimeout = Objects.requireNonNull(stepTimeout, "stepTimeout");
        this.overallTimeout = Objects.requireNonNull(overallTimeout, "overallTimeout");
        this.contextTokens = contextTokens <= 0 ? PromptBudget.DEFAULT_CONTEXT_TOKENS : contextTokens;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "chat-orchestrator-step");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 关闭步骤执行器。上下文关闭时调用；正在运行的编排会被中断。 */
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 寒暄的固定回复：不调模型、不检索，因此既不花 token，也不会被无关片段带偏。
     *
     * <p>用模板而不是让模型自由发挥，是因为「你好」的回答不该取决于检索到了什么；模型在这里唯一能做的
     * 事情就是把上下文里的噪声编成一句像样的话。</p>
     *
     * @param agent 当前 Agent，用于取名字；缺失时用中性称呼
     * @return 直接展示给用户的答复
     */
    static String smallTalkAnswer(AgentSnapshot agent) {
        String name = agent == null || agent.name() == null || agent.name().isBlank()
                ? "知识库助手" : agent.name().trim();
        return "你好！我是「" + name + "」，可以基于已配置的知识库回答问题。\n\n"
                + "直接说要查的内容就行，比如某项制度的适用范围、核算边界、核查要点或数据来源。";
    }

    /** 执行一次完整的四阶段编排；只有入参非法才抛校验异常，运行期失败一律降级而不外抛。 */
    public OrchestrationResult run(AgentSnapshot agent, String question, StepSink sink) {
        Objects.requireNonNull(agent, "agent");
        if (question == null || question.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "问题不能为空");
        }
        StepSink events = sink == null ? StepSink.NOOP : sink;
        String trimmedQuestion = question.trim();

        List<ReasoningStepData> steps = new ArrayList<>();

        // ---------------------------------------------------------------- 寒暄短路
        // 「你好」不是知识库问题。走下去只会拿 8 段噪声凑数、再被判「证据不足」、再补一轮，
        // 最后基于噪声写出一篇像模像样的「咨询建议」——比慢更糟的是它看起来是对的。
        if (SmallTalk.isSmallTalk(trimmedQuestion)) {
            long smallTalkStartedAt = System.nanoTime();
            String answer = smallTalkAnswer(agent);
            String summary = "寒暄或元问题，未检索直接回复";
            events.onStepStart(1, Phase.ANSWER);
            steps.add(step(1, Phase.ANSWER, summary, "{\"citations\":0,\"direct\":true}", smallTalkStartedAt));
            events.onStepEnd(1, Phase.ANSWER, summary);
            events.onDelta(Phase.ANSWER, answer);
            return new OrchestrationResult(answer, steps, List.of(), false, TokenUsage.ZERO);
        }

        // ---------------------------------------------------------------- 实时工具短路
        // 「北京天气怎么样」这类问题在知识库里必然零召回，走完整流水线只会得到一句「没有找到依据」。
        // 工具可用且问题明确要实时数据时，直接让模型调工具作答。工具失败或超时就当没发生过、
        // 继续走检索——降级路径必须存在，否则一个 MCP 服务抖动就能让天气问题变成会话失败。
        if (gateway.hasLiveTools() && ToolRouting.wantsLiveData(trimmedQuestion)) {
            long toolStartedAt = System.nanoTime();
            try {
                String toolAnswer = timed(() -> gateway.chatWithTools(agent.chatModelId(),
                        agent.temperature(), PromptTemplates.toolSystem(agent.name()),
                        trimmedQuestion));
                if (toolAnswer != null && !toolAnswer.isBlank()) {
                    // 步骤等工具真正返回后才发：失败时事件流里不该留下一条半截的 ANSWER。
                    String summary = "调用实时工具作答";
                    events.onStepStart(1, Phase.ANSWER);
                    steps.add(step(1, Phase.ANSWER, summary,
                            "{\"citations\":0,\"tools\":true}", toolStartedAt));
                    events.onStepEnd(1, Phase.ANSWER, "已生成");
                    events.onDelta(Phase.ANSWER, toolAnswer);
                    return new OrchestrationResult(toolAnswer, steps, List.of(), false,
                            TokenUsage.ZERO);
                }
            } catch (RuntimeException e) {
                log.warn("工具作答失败，回退到知识库检索: {}", e.getMessage());
            }
        }

        int maxRounds = Math.min(Math.max(agent.maxRounds(), 1), HARD_MAX_ROUNDS);
        long deadlineNanos = System.nanoTime() + overallTimeout.toNanos();
        LinkedHashMap<Long, RetrievedChunk> evidence = new LinkedHashMap<>();
        int consecutiveFailures = 0;
        boolean truncated = false;
        String draft = null;
        int round = 1;
        int previousEvidenceSize = 0;

        // ---------------------------------------------------------------- PLAN（仅一次）
        events.onStepStart(round, Phase.PLAN);
        long planStartedAt = System.nanoTime();
        List<String> queries;
        boolean planParsed = true;
        try {
            PlanResult raw = timed(() -> gateway.chatStructured(agent.chatModelId(),
                    agent.temperature(), PromptTemplates.planSystem(),
                    PromptTemplates.planUser(trimmedQuestion), PlanResult.class));
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

        // ---------------------------------------------------------------- RETRIEVE / REFLECT 循环
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

            if (retrieved.isEmpty()) {
                // 一条都没召回：REFLECT 的全部工作就是判断「这些片段够不够」，没有片段可判，
                // 它只会对着「（无）」再编一轮查询，白白多花两次模型调用。直接收敛到 ANSWER。
                log.debug("No evidence retrieved on round {}; ending the loop", round);
                break;
            }
            if (evidence.size() == previousEvidenceSize) {
                // 本轮召回的片段全是已有的（措辞微调后的重复检索）：再看一遍同一批证据不会有新结论。
                log.debug("Round {} added no new evidence; ending the loop", round);
                break;
            }
            previousEvidenceSize = evidence.size();

            events.onStepStart(round, Phase.REFLECT);
            startedAt = System.nanoTime();
            boolean reflectParsed = true;
            ReflectResult reflect;
            final String draftSoFar = draft;
            final int reflectRound = round;
            // 证据按「窗口 - 输出预留 - 系统提示/问题/草稿」的余额裁剪；不裁剪的话第 2、3 轮必然超窗。
            String reflectSystem = PromptTemplates.reflectSystem();
            int reflectBudget = PromptBudget.evidenceTokens(contextTokens,
                    PromptBudget.estimateTokens(reflectSystem)
                            + PromptBudget.estimateTokens(trimmedQuestion)
                            + PromptBudget.estimateTokens(draftSoFar)
                            + PromptBudget.PROMPT_FRAME_TOKENS);
            final String reflectEvidence = PromptTemplates.numberedEvidence(
                    List.copyOf(evidence.values()), reflectBudget);
            try {
                ReflectResult raw = timed(() -> gateway.chatStructured(agent.chatModelId(),
                        agent.temperature(), reflectSystem,
                        PromptTemplates.reflectUser(trimmedQuestion, reflectEvidence,
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
                if (e.getCode() == ErrorCode.MODEL_CONTEXT_EXCEEDED) {
                    // 预算没兜住（问题或草稿本身就很长）：不再重试，直接带着已有证据作答。
                    truncated = true;
                    steps.add(step(round, Phase.REFLECT, "反思提示词超出模型上下文，按已有证据作答",
                            CONTEXT_PAYLOAD, startedAt));
                    events.onStepEnd(round, Phase.REFLECT, "上下文超出");
                    log.warn("REFLECT prompt exceeded model context on round {}: {}", round, e.getMessage());
                    break;
                }
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

        // ---------------------------------------------------------------- ANSWER（作答）
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
            String answerSystem = PromptTemplates.answerSystem(agent.systemPrompt());
            int answerBudget = PromptBudget.evidenceTokens(contextTokens,
                    PromptBudget.estimateTokens(answerSystem)
                            + PromptBudget.estimateTokens(trimmedQuestion)
                            + PromptBudget.PROMPT_FRAME_TOKENS);
            final String numbered = PromptTemplates.numberedEvidence(citations, answerBudget);
            try {
                answer = timed(() -> gateway.chat(agent.chatModelId(), agent.temperature(),
                        answerSystem,
                        PromptTemplates.answerUser(trimmedQuestion, numbered)));
                steps.add(step(round, Phase.ANSWER, "基于 " + citations.size() + " 个片段生成答案",
                        "{\"citations\":" + citations.size() + "}", startedAt));
                events.onStepEnd(round, Phase.ANSWER, "已生成");
            } catch (StepTimedOutException e) {
                truncated = true;
                answer = firstNonBlank(draft, NO_EVIDENCE_ANSWER);
                steps.add(step(round, Phase.ANSWER, "终答超时，返回最后一轮草稿", TIMEOUT_PAYLOAD, startedAt));
                events.onStepEnd(round, Phase.ANSWER, "超时");
            } catch (AppException e) {
                if (e.getCode() != ErrorCode.MODEL_CONTEXT_EXCEEDED) {
                    throw e; // 其它提供商故障（不可达、401、模型不存在）仍按 FAILED 上抛，保留真实原因
                }
                truncated = true;
                answer = CONTEXT_EXCEEDED_ANSWER;
                steps.add(step(round, Phase.ANSWER, "提示词超出模型上下文，未生成答案",
                        CONTEXT_PAYLOAD, startedAt));
                events.onStepEnd(round, Phase.ANSWER, "上下文超出");
                log.warn("ANSWER prompt exceeded model context: {}", e.getMessage());
            }
        }
        if (answer == null || answer.isBlank()) {
            answer = firstNonBlank(draft, NO_EVIDENCE_ANSWER);
        }
        events.onDelta(Phase.ANSWER, answer);

        return new OrchestrationResult(answer, steps, citations, truncated, TokenUsage.ZERO);
    }

    // ------------------------------------------------------------------ 步骤执行

    /** 在单步超时约束下执行一次模型或检索调用；本方法自身的失败会原样抛出，由调用方决定降级方式。 */
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
            throw new IllegalStateException("编排步骤执行失败", cause);
        }
    }

    // ------------------------------------------------------------------ 归一化

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

    /** 下一轮要用的检索语句：优先用 REFLECT 给出的语句，其次用它指出的缺口，最后才复用当前语句。 */
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

    // ------------------------------------------------------------------ 小工具

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
            return "{}"; // 仅用于日志的载荷，绝不能因为它让整个提问失败
        }
    }

    /** 内部信号：不是业务错误，也绝不会逃出 {@link #run}。 */
    private static final class StepTimedOutException extends RuntimeException {
        private StepTimedOutException() {
            super("orchestration step timed out", null, false, false);
        }
    }
}
