package com.advisory.chat.orchestration;

import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.util.List;

/**
 * The fixed prompts of the four-phase pipeline (spec §11, plan Task 11 step 3).
 *
 * <p>They are constants, not configuration: the orchestration strategy is deliberately not
 * user-configurable, and a prompt change must be a code review, not a database edit. The citation
 * contract lives in {@link #answerSystem()}: numbered snippets only, {@code [n]} after every claim,
 * and an explicit "not found" instead of guessing. The same numbering is produced by
 * {@link #numberedEvidence(List)}, so {@code [n]} in the answer maps to {@code citations[n-1]}
 * without any post-processing.</p>
 */
public final class PromptTemplates {

    private static final String NO_NODE = "未归类";

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
     * The fixed ANSWER rules. Kept as a constant because they are the citation contract: an agent
     * author may add a persona, but may not weaken "cite every claim / never guess" (spec §4).
     */
    private static final String ANSWER_RULES = """
            你是企业知识库问答助手。只能使用用户提供的编号片段作答，禁止使用片段之外的知识。
            要求：
            - 每个论断后用 [n] 标注来源编号，例如 [1]、[2]；多个片段支持同一论断时并列标注，如 [1][3]；
            - 片段不足以回答时，明确说明"知识库中没有找到相关依据"，不要猜测、不要补全；
            - 输出 Markdown 正文，不要复述片段原文，不要输出参考文献列表。
            """;

    /**
     * The ANSWER system prompt with the agent's own {@code system_prompt} in front of the fixed
     * rules.
     *
     * <p>Only ANSWER is personalised: PLAN and REFLECT are structural steps that must emit parsable
     * JSON, and a free-form persona prompt there is a reliable way to break parsing. The rules go
     * last and are declared to win on conflict, so an agent prompt cannot silently disable the
     * citation contract.</p>
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

    /** Renders the evidence as {@code [n] 文档名（节点：…）} followed by the snippet text. */
    public static String numberedEvidence(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append(emptyAs(chunk.docName(), "未知文档"))
                    .append("（节点：").append(emptyAs(chunk.nodeName(), NO_NODE)).append("）\n")
                    .append(emptyAs(chunk.content(), ""))
                    .append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String emptyAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
