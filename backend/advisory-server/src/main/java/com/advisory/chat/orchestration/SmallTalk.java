package com.advisory.chat.orchestration;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 寒暄与元问题的识别：这类输入不该进检索，更不该进多轮反思。
 *
 * <p>「你好」并不是「一个很难检索到的问题」，它<em>不是知识库问题</em>。让它走 PLAN → RETRIEVE → REFLECT
 * 的代价不只是慢：向量检索没有相关性下限，一定会返回 8 个「最像」的片段；REFLECT 看到 8 段噪声只能判「证据
 * 不足」，于是进入下一轮；第二轮再拿同样无关的证据去判，直到轮次用尽——最后 ANSWER 还会基于这堆噪声硬写
 * 一篇「咨询建议」。寒暄必须在进入流水线之前就被拦下来。</p>
 *
 * <p>判据刻意保守：<b>去掉标点与语气词后必须整句都是寒暄词</b>，且长度不超过
 * {@value #MAX_LENGTH} 个字。「你好，碳排放核算边界怎么划定？」这种带正题的问候不算——它只有前缀是寒暄，
 * 剥离后仍剩下实质内容。宁可漏判（继续走正常检索），也不要误判（把真问题答成一句问候）。</p>
 */
final class SmallTalk {

    /** 归一化后的长度上限；超过这个长度基本可以确定不是纯寒暄。 */
    static final int MAX_LENGTH = 12;

    /**
     * 可按前缀剥离的寒暄片段，按长度降序排列，保证「你是谁」不会被「你」这类短词抢先切掉。
     *
     * <p>英文全小写：归一化时已经统一转成小写。</p>
     */
    private static final List<String> PHRASES = List.of(
            "你能做什么", "你会什么", "你叫什么", "你是谁", "怎么用", "有人吗", "在不在",
            "早上好", "上午好", "中午好", "下午好", "晚上好",
            "谢谢你", "多谢", "感谢", "再见", "拜拜", "帮助",
            "你好", "您好", "哈喽", "在吗", "在么", "谢谢", "好的", "收到", "嗯", "在",
            "hello", "hi", "hey", "bye", "help", "ok")
            .stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    /** 句末语气词：先剥掉它们，剩下的才有机会命中 {@link #PHRASES}。 */
    private static final String PARTICLES = "呀啊哈呢吧啦哦嘛呗吗";

    private SmallTalk() {
    }

    /**
     * 判断一句话是否只是寒暄/元问题。
     *
     * @param question 用户输入，可为 {@code null}
     * @return 去掉标点与语气词后全部由寒暄片段组成时为 {@code true}
     */
    static boolean isSmallTalk(String question) {
        String rest = normalize(question);
        if (rest.isEmpty() || rest.length() > MAX_LENGTH) {
            return false;
        }
        // 逐段剥离：能整句剥空才算纯寒暄（"你好谢谢" 算，"你好碳排放" 不算）。
        boolean stripped = true;
        while (stripped && !rest.isEmpty()) {
            stripped = false;
            rest = stripTrailingParticles(rest);
            for (String phrase : PHRASES) {
                if (rest.startsWith(phrase)) {
                    rest = rest.substring(phrase.length());
                    stripped = true;
                    break;
                }
            }
        }
        return rest.isEmpty();
    }

    /** 去掉空白、标点，并统一成小写。语气词留到剥离阶段再处理，避免「哈喽」被拆成「喽」。 */
    private static String normalize(String question) {
        if (question == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(question.length());
        for (char ch : question.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(ch) || isPunctuation(ch)) {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String stripTrailingParticles(String text) {
        int end = text.length();
        while (end > 0 && PARTICLES.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end);
    }

    private static boolean isPunctuation(char ch) {
        return "，。！？、；：,.!?;:~…—_-【】[]（）()「」\"'“”‘’".indexOf(ch) >= 0;
    }
}
