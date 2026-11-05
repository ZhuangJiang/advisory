package com.advisory.chat.orchestration;

import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 四阶段流水线使用的固定提示词（spec §11，plan Task 11 第 3 步）。
 *
 * <p>它们是常量而非配置：编排策略有意不开放给用户调整，改提示词应当走代码评审而不是改数据库。
 * 引用契约写在 {@link #answerSystem()} 里：只用编号片段作答、每个论断后标 {@code [n]}、找不到就明确说
 * 「未找到」而不是猜。编号同样由 {@link #numberedEvidence(List)} 生成，因此答案里的 {@code [n]}
 * 无需任何后处理就能映射到 {@code citations[n-1]}。</p>
 */
public final class PromptTemplates {

    private static final String NO_NODE = "未归类";

    /** 单片段至少要有这么多 token，否则整段省略而不是留一句无意义的话。 */
    private static final int MIN_CHUNK_TOKENS = 120;

    private PromptTemplates() {
    }

    public static String planSystem() {
        return """
                你是检索规划助手。根据用户问题生成 1-3 条互补的检索查询，覆盖不同角度：\
                同义说法、上下位概念、文档中可能出现的措辞。
                只输出 JSON，不要解释，不要 Markdown 代码块，格式：
                {"queries":["查询1","查询2"]}
                """;
    }

    public static String planUser(String question) {
        return "用户问题：\n" + question;
    }

    public static String reflectSystem() {
        return """
                你在判断已召回的片段是否足以回答问题。
                输入包含：用户问题、按编号排列的片段、上一轮草稿（可能为空）。
                只输出 JSON，不要解释，不要 Markdown 代码块，格式：
                {"sufficient":true,"gaps":["仍然缺失的信息"],"nextQueries":["下一轮检索查询"],"draftAnswer":"基于片段的草稿"}
                规则：
                - 片段的直接内容能支撑问题的关键结论时 sufficient=true；
                - sufficient=false 时，nextQueries 给出 1-3 条针对缺口的新查询，避免重复上一轮；
                - draftAnswer 只能使用片段中的内容，禁止编造；没有依据时写"知识库中没有找到"。
                """;
    }

    public static String reflectUser(String question, String numberedEvidence, String draftAnswer,
                                     int round, int maxRounds) {
        return """
                用户问题：
                %s

                已召回片段：
                %s

                上一轮草稿：
                %s

                当前是第 %d 轮，最多 %d 轮。
                """.formatted(question, numberedEvidence, emptyAs(draftAnswer, "（无）"), round, maxRounds);
    }

    /**
     * ANSWER 的固定规则，以常量形式固化。
     *
     * <p>这几条就是引用契约：Agent 的作者可以追加人设与语气，但不能削弱「每个论断都要引用、绝不猜测」
     * 这两条底线（spec §4）。</p>
     */
    private static final String ANSWER_RULES = """
            你是企业知识库问答助手。只能使用用户提供的编号片段作答，禁止使用片段之外的知识。
            要求：
            - 每个论断后用 [n] 标注来源编号，例如 [1]、[2]；多个片段支持同一论断时并列标注，如 [1][3]；
            - 片段不足以回答时，明确说明"知识库中没有找到相关依据"，不要猜测、不要补全；
            - 输出 Markdown 正文，不要复述片段原文，不要输出参考文献列表。
            """;

    /**
     * 把 Agent 自己的 {@code system_prompt} 放在固定规则之前，组成 ANSWER 的系统提示词。
     *
     * <p>只有 ANSWER 会做个性化：PLAN 与 REFLECT 是必须产出可解析 JSON 的结构化步骤，在这里塞一段自由
     * 发挥的人设提示词，几乎必然把解析搞坏。固定规则放在最后并声明冲突时以其为准，因此 Agent 的提示词
     * 无法悄悄废掉引用契约。</p>
     *
     * @param agentSystemPrompt Agent 自定义的系统提示词，可为空
     * @return 拼好的系统提示词
     */
    public static String answerSystem(String agentSystemPrompt) {
        String role = agentSystemPrompt == null ? "" : agentSystemPrompt.trim();
        if (role.isEmpty()) {
            return ANSWER_RULES;
        }
        return role + "\n\n必须同时遵守以下输出规则（与上述角色设定冲突时以本节为准）：\n" + ANSWER_RULES;
    }

    public static String answerSystem() {
        return ANSWER_RULES;
    }

    public static String answerUser(String question, String numberedEvidence) {
        return """
                用户问题：
                %s

                可用片段：
                %s
                """.formatted(question, numberedEvidence);
    }

    /** 把证据渲染成 {@code [n] 文档名（节点：…）} 加片段正文的形式，不限制长度。 */
    public static String numberedEvidence(List<RetrievedChunk> chunks) {
        return numberedEvidence(chunks, Integer.MAX_VALUE);
    }

    /**
     * 按 token 预算渲染编号证据。
     *
     * <p>编号始终取片段在列表中的<em>原始</em>下标（{@code [1]}、{@code [2]}…），被预算挤掉的片段
     * 只是不出现在提示词里，编号也不重排——{@code citations[n-1]} 与答案里的 {@code [n]} 因此仍然严格
     * 对应。末尾会显式注明省略了几个片段，免得模型把「没看到」误当成「不存在」。</p>
     *
     * <p>装不下时优先截断当前片段的正文（并标注），而不是直接丢弃：半段证据通常仍比没有强。
     * 预算极小时至少保留第一个片段的一部分，避免证据区完全为空。</p>
     *
     * @param chunks      检索到的片段，按引用顺序
     * @param budgetTokens 证据可用的 token 预算，{@link Integer#MAX_VALUE} 表示不限制
     * @return 渲染后的编号证据
     */
    public static String numberedEvidence(List<RetrievedChunk> chunks, int budgetTokens) {
        if (chunks == null || chunks.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int omitted = 0;
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            String header = "[" + (i + 1) + "] "
                    + emptyAs(chunk.docName(), "未知文档")
                    + "（节点：" + emptyAs(chunk.nodeName(), NO_NODE) + "）\n";
            String content = emptyAs(chunk.content(), "");
            int headerTokens = PromptBudget.estimateTokens(header);
            int remaining = budgetTokens - used - headerTokens;
            if (remaining < MIN_CHUNK_TOKENS && i > 0) {
                omitted = chunks.size() - i;
                break;
            }
            int cap = Math.max(remaining, MIN_CHUNK_TOKENS);
            boolean truncated = PromptBudget.estimateTokens(content) > cap;
            if (truncated) {
                content = PromptBudget.truncateToTokens(content, cap) + "\n（片段过长，已截断）";
            }
            sb.append(header).append(content).append("\n\n");
            used += headerTokens + PromptBudget.estimateTokens(content);
            if (truncated) {
                omitted = chunks.size() - i - 1;
                break;
            }
        }
        if (omitted > 0) {
            sb.append("（为控制上下文长度，另有 ").append(omitted).append(" 个片段未列入）");
        }
        return sb.toString().stripTrailing();
    }

    private static String emptyAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
