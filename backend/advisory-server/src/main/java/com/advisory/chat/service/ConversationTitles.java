package com.advisory.chat.service;

/**
 * 会话标题的生成规则。
 *
 * <p>标题取自会话的<em>第一个问题</em>，而不是调一次模型去「起名」：那样每次开新会话都要多付一次
 * 推理、多等几秒，还会带来第二种失败模式（起名模型报错要不要影响提问？）。截断问题没有这类问题，
 * 对「报销时限是多久」这种真实提问也足够区分——用户扫一眼侧栏就知道在聊什么。</p>
 *
 * <p>截断按<em>码点</em>计数，不按 {@code char}：中文虽在 BMP 内，但问题里可能夹 emoji，
 * 按 char 切会把代理对劈成两半，写进 {@code VARCHAR(255)} 也救不回来。</p>
 */
public final class ConversationTitles {

    /** 标题最多保留的码点数；超出的部分丢弃并以「…」结尾。 */
    static final int MAX_CODE_POINTS = 24;

    /** 句末标点：截断时先摘掉，避免出现「……怎么划定？…」这种叠标点。 */
    private static final String TRAILING_PUNCTUATION = "。．，,；;：:！!？?、 \t\r\n";

    private ConversationTitles() {
    }

    /**
     * 由问题推导会话标题。
     *
     * @param question 用户的首个问题
     * @return 归一化后的标题；问题为空或全是标点时返回 {@code null}（此时调用方保持「未命名」）
     */
    public static String derive(String question) {
        if (question == null) {
            return null;
        }
        String flat = collapse(question);
        flat = stripTrailingPunctuation(flat);
        if (flat.isEmpty()) {
            return null;
        }
        int length = flat.codePointCount(0, flat.length());
        if (length <= MAX_CODE_POINTS) {
            return flat;
        }
        int end = flat.offsetByCodePoints(0, MAX_CODE_POINTS);
        String head = stripTrailingPunctuation(flat.substring(0, end).strip());
        return head.isEmpty() ? null : head + "…";
    }

    /** 换行、缩进、连续空格都压成一个空格：侧栏是一行文本，标题里不该出现换行。 */
    private static String collapse(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i += Character.charCount(raw.codePointAt(i))) {
            int cp = raw.codePointAt(i);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static String stripTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(value.charAt(end - 1)) >= 0) {
            end -= 1;
        }
        return value.substring(0, end);
    }
}
