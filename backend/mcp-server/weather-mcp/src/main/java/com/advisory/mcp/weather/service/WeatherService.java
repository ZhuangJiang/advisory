package com.advisory.mcp.weather.service;

import com.advisory.mcp.weather.config.WeatherProperties;
import com.advisory.mcp.weather.model.DailyForecast;
import com.advisory.mcp.weather.model.WeatherNow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 按配置选择数据源。做成「一个 Bean 内部转发」而不是两个 @ConditionalOnProperty 的 Bean：
 * 配置写错（比如 WEATHER_PROVIDER=openmeteo）时可以在启动阶段抛一条看得懂的错误，
 * 而不是在工具被调用时抛出 NoSuchBeanDefinition。
 */
@Component
public class WeatherService {

    private final WeatherProvider delegate;

    public WeatherService(OpenMeteoProvider openMeteo, StubProvider stub, WeatherProperties properties) {
        String configured = properties.provider() == null || properties.provider().isBlank()
                ? "open-meteo"
                : properties.provider().trim().toLowerCase(Locale.ROOT);

        this.delegate = switch (configured) {
            case "open-meteo" -> openMeteo;
            case "stub" -> stub;
            default -> throw new IllegalStateException(
                    "app.weather.provider 只支持 open-meteo | stub，当前为：" + properties.provider());
        };
    }

    public WeatherNow current(String city) {
        return delegate.current(city);
    }

    public List<DailyForecast> forecast(String city, int days) {
        return delegate.forecast(city, days);
    }

    public String sourceName() {
        return delegate.sourceName();
    }
}
