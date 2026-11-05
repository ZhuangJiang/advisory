package com.advisory.mcp.weather.model;

/** 实况天气。时间沿用上游返回的当地时间字符串（timezone=auto），不做二次转换。 */
public record WeatherNow(GeoLocation location, double temperatureC, double apparentC,
                         double humidity, double windKph, String condition, String observedAt) {
}
