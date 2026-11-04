package com.advisory.chat.orchestration;

import java.util.List;

/**
 * REFLECT 的结构化输出。
 *
 * @param sufficient  现有片段是否已足以作答
 * @param gaps        仍然缺少的信息（会回填到下一轮的提示词中）
 * @param nextQueries 下一轮检索要用的语句，优先于 {@code gaps}
 * @param draftAnswer 仅依据现有片段写成的草稿，最后一次调用超时就用它作为 ANSWER 的兜底
 */
public record ReflectResult(boolean sufficient, List<String> gaps, List<String> nextQueries,
                            String draftAnswer) {
}
