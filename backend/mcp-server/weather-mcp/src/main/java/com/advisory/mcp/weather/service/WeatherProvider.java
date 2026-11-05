package com.advisory.mcp.weather.service;

import com.advisory.mcp.weather.model.DailyForecast;
import com.advisory.mcp.weather.model.WeatherNow;

import java.util.List;

/**
 * 天气数据来源。目前两个实现：Open-Meteo（真实）与 Stub（离线）。
 * 工具层只依赖这个接口，换数据源时不用动工具与文案。
 */
public interface WeatherProvider {

    /** 展示给用户的来源名，让「这是假数据」这件事在输出里可见。 */
    String sourceName();

    WeatherNow current(String city);

    List<DailyForecast> forecast(String city, int days);
}
