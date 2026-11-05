package com.advisory.mcp.weather.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 天气服务配置。
 *
 * @param provider           open-meteo | stub（stub 返回固定假数据，供离线开发与测试）
 * @param timeout            单次上游请求的超时；两个上游都不可靠时宁可快速失败，也不要卡住 MCP 调用
 * @param geocodingCacheTtl  城市→经纬度的缓存时长，地理编码几乎不变，缓存可以很长
 * @param geocodingBaseUrl   Open-Meteo Geocoding API 根地址
 * @param forecastBaseUrl    Open-Meteo Forecast API 根地址
 */
@ConfigurationProperties("app.weather")
public record WeatherProperties(
        @DefaultValue("open-meteo") String provider,
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("1h") Duration geocodingCacheTtl,
        @DefaultValue("https://geocoding-api.open-meteo.com") String geocodingBaseUrl,
        @DefaultValue("https://api.open-meteo.com") String forecastBaseUrl) {
}
