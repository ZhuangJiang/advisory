package com.advisory.mcp.weather.support;

import java.util.Map;

/**
 * WMO 天气代码 → 中文描述。MCP 工具的返回值最终进模型上下文，
 * 翻译成人话比丢一个 61 给模型再去猜要可靠得多。
 */
public final class WeatherCodes {

    private static final Map<Integer, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry(0, "晴"),
            Map.entry(1, "晴间多云"),
            Map.entry(2, "多云"),
            Map.entry(3, "阴"),
            Map.entry(45, "有雾"),
            Map.entry(48, "雾凇"),
            Map.entry(51, "小毛毛雨"),
            Map.entry(53, "毛毛雨"),
            Map.entry(55, "大毛毛雨"),
            Map.entry(56, "轻微冻雨"),
            Map.entry(57, "冻雨"),
            Map.entry(61, "小雨"),
            Map.entry(63, "中雨"),
            Map.entry(65, "大雨"),
            Map.entry(66, "轻微冻雨"),
            Map.entry(67, "强冻雨"),
            Map.entry(71, "小雪"),
            Map.entry(73, "中雪"),
            Map.entry(75, "大雪"),
            Map.entry(77, "米雪"),
            Map.entry(80, "小阵雨"),
            Map.entry(81, "阵雨"),
            Map.entry(82, "强阵雨"),
            Map.entry(85, "小阵雪"),
            Map.entry(86, "大阵雪"),
            Map.entry(95, "雷阵雨"),
            Map.entry(96, "雷阵雨伴小冰雹"),
            Map.entry(99, "雷阵雨伴大冰雹"));

    private WeatherCodes() {
    }

    public static String describe(int code) {
        return DESCRIPTIONS.getOrDefault(code, "未知天气(" + code + ")");
    }
}
