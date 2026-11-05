package com.advisory.mcp.weather.support;

/** 上游天气服务不可用（超时、5xx、返回体异常）。 */
public class WeatherUnavailableException extends RuntimeException {

    public WeatherUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
