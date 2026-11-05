package com.advisory.mcp.weather.model;

/** 单日预报。date 为 ISO 日期（yyyy-MM-dd）。 */
public record DailyForecast(String date, String condition, double maxC, double minC) {
}
