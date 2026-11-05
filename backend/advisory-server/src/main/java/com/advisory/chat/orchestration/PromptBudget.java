package com.advisory.chat.orchestration;

/**
 * 提示词的 token 预算：把「模型上下文窗口」换算成「证据还能占多少」。
 *
 * <p>上下文窗口是硬边界：prompt 一旦超了，提供商直接返回 400（Ollama 的
 * {@code exceed_context_size_error}、OpenAI 兼容端点的 {@code maximum context length}），
 * 而不是替你截断。编排器的证据跨轮累积，不按窗口裁剪的话，几轮之后必然超限——本地小模型默认
 * 只有 4096，第一、二轮就可能顶到天花板。</p>
 *
 * <p>估算刻意偏保守：CJK 按 1 token/字，其余按 4 字符/token。不同 tokenizer 的差异由
 * {@link #RESERVED_OUTPUT_TOKENS} 与 {@link #SAFETY_MARGIN_TOKENS} 吸收；真正的兜底是编排器对
 * 「超窗」错误的降级处理，而不是指望估算绝对准确。</p>
 */
public final class PromptBudget {

    /** 未配置时的默认窗口。与 {@code app.chat.context-window-tokens} 的默认值保持一致。 */
    public static final int DEFAULT_CONTEXT_TOKENS = 8192;

    /** 留给模型生成答案的空间；提示词把窗口占满会导致输出被立刻截断。 */
    public static final int RESERVED_OUTPUT_TOKENS = 1024;

    /** 估算误差的余量。 */
    static final int SAFETY_MARGIN_TOKENS = 256;

    /** 即使窗口再小，也至少给证据留这么多：没有证据时整条链路就没有意义。 */
    static final int MIN_EVIDENCE_TOKENS = 320;

    /** 问题、轮次说明、JSON 格式说明等固定框架的粗略开销。 */
    static final int PROMPT_FRAME_TOKENS = 64;

    private PromptBudget() {
    }

    /**
     * 估算一段文本占用的 token 数。
     *
     * @param text 任意文本，可为 {@code null}
     * @return 估算值；空文本为 0
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 以 1/4 token 为单位累加：CJK 每字 4 个单位（1 token），其余每字符 1 个单位（4 字符/token）。
        int quarterTokens = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            quarterTokens += isCjk(codePoint) ? 4 : 1;
            index += Character.charCount(codePoint);
        }
        return (quarterTokens + 3) / 4;
    }

    /**
     * 计算证据可以占用的 token 预算。
     *
     * @param contextTokens     模型窗口；非正数时使用 {@link #DEFAULT_CONTEXT_TOKENS}
     * @param fixedPromptTokens 系统提示词、问题、草稿等不可避免的占用
     * @return 证据预算，至少 {@link #MIN_EVIDENCE_TOKENS}
     */
    public static int evidenceTokens(int contextTokens, int fixedPromptTokens) {
        int window = contextTokens <= 0 ? DEFAULT_CONTEXT_TOKENS : contextTokens;
        int usable = window - RESERVED_OUTPUT_TOKENS - SAFETY_MARGIN_TOKENS
                - PROMPT_FRAME_TOKENS - Math.max(0, fixedPromptTokens);
        return Math.max(MIN_EVIDENCE_TOKENS, usable);
    }

    /**
     * 按 token 预算截断文本，切点落在码点边界上（不会劈开 emoji 或代理对）。
     *
     * @param text   原文本
     * @param budget 允许的 token 数
     * @return 不超过预算的前缀；预算非正时返回空串
     */
    static String truncateToTokens(String text, int budget) {
        if (text == null || text.isEmpty() || budget <= 0) {
            return "";
        }
        int limit = budget * 4;
        int quarterTokens = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int cost = isCjk(codePoint) ? 4 : 1;
            if (quarterTokens + cost > limit) {
                break;
            }
            quarterTokens += cost;
            index += Character.charCount(codePoint);
        }
        return text.substring(0, index);
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL
                || (codePoint >= 0x3000 && codePoint <= 0x303F)   // CJK 标点
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);  // 全角字符
    }
}
