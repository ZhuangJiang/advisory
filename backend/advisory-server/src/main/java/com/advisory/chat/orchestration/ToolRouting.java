package com.advisory.chat.orchestration;

import java.util.List;
import java.util.Locale;

/**
 * 判断一个问题是否需要「实时数据」而不是知识库检索。
 *
 * <p>为什么用关键词而不是让模型先分类：多一次模型调用就多一次失败模式，而且这一步必须在 PLAN 之前、
 * 在 180 秒总预算之内完成。词表刻意保守——只收天气语义明确的说法（「气温」「下雨」「预报」），
 * 不收「温度」「湿度」这类在碳核算、设备文档里同样常见的词，误判的代价是拿天气答案盖掉业务问题。</p>
 *
 * <p>匹配只做子串包含，对中英文都够用；命中之后仍然要工具真的可用（见
 * {@link ChatModelGateway#hasLiveTools()}），两个条件同时满足才走工具分支。</p>
 */
public final class ToolRouting {

    private static final List<String> LIVE_DATA_TERMS = List.of(
            "天气", "气温", "下雨", "降雨", "雨伞", "下雪", "降雪", "预报", "风速", "风力",
            "台风", "紫外线", "空气质量",
            "weather", "forecast", "temperature", "rain", "snow", "umbrella");

    private ToolRouting() {
    }

    /**
     * @param question 用户原始问题
     * @return 命中实时数据语义时为 {@code true}
     */
    public static boolean wantsLiveData(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        for (String term : LIVE_DATA_TERMS) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
