package com.advisory.mcp.weather.tool;

import com.advisory.mcp.weather.model.DailyForecast;
import com.advisory.mcp.weather.model.WeatherNow;
import com.advisory.mcp.weather.service.WeatherService;
import com.advisory.mcp.weather.support.CityNotFoundException;
import com.advisory.mcp.weather.support.WeatherUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * MCP 工具本体。
 *
 * 两个约定：
 * 1. 工具永不抛异常。异常到了模型那里只是一段报错文本，还会打断它组织回答；
 *    这里把它们翻译成「能拿去问用户」的话直接返回。
 * 2. 返回值是给模型读的纯文本，不是 JSON。字段少、语义清楚，模型不容易读错。
 */
@Component
public class WeatherTools {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 7;

    private final WeatherService weather;

    public WeatherTools(WeatherService weather) {
        this.weather = weather;
    }

    @Tool(name = "get_current_weather",
            description = "查询指定城市的实时天气，包含天气现象、气温、体感温度、相对湿度与风速。"
                    + "适用于「今天天气怎么样」「现在冷不冷」「要带伞吗」这类问题。")
    public String getCurrentWeather(
            @ToolParam(description = "城市名，中英文均可，例如：北京、杭州、Shanghai、Tokyo") String city) {

        String keyword = normalizeCity(city);
        if (keyword == null) {
            return "请提供城市名，例如「北京」或「Shanghai」。";
        }

        try {
            WeatherNow now = weather.current(keyword);
            return String.format(Locale.ROOT, """
                            %s 当前天气：
                            - 天气：%s
                            - 气温：%.1f ℃（体感 %.1f ℃）
                            - 相对湿度：%.0f%%
                            - 风速：%.1f km/h
                            - 观测时间：%s（当地时间）
                            数据来源：%s""",
                    now.location().displayName(), now.condition(), now.temperatureC(), now.apparentC(),
                    now.humidity(), now.windKph(), now.observedAt(), weather.sourceName());
        } catch (CityNotFoundException e) {
            return cityNotFound(city);
        } catch (WeatherUnavailableException e) {
            log.warn("实时天气查询失败, city={}", keyword, e);
            return "天气服务暂时不可用，请稍后再试。";
        } catch (RuntimeException e) {
            log.error("实时天气查询异常, city={}", keyword, e);
            return "查询天气时发生异常，请稍后再试。";
        }
    }

    @Tool(name = "get_weather_forecast",
            description = "查询指定城市未来 1 到 7 天的天气预报，包含每日天气现象与最高/最低气温。"
                    + "适用于「明天要下雨吗」「这周适合出去玩吗」这类问题。")
    public String getWeatherForecast(
            @ToolParam(description = "城市名，中英文均可，例如：北京、杭州、Shanghai、Tokyo") String city,
            @ToolParam(description = "预报天数，1 到 7 之间的整数；用户没说时用 3") int days) {

        String keyword = normalizeCity(city);
        if (keyword == null) {
            return "请提供城市名，例如「北京」或「Shanghai」。";
        }
        if (days < MIN_DAYS || days > MAX_DAYS) {
            return String.format("预报天数必须在 %d 到 %d 之间，收到的是 %d，请用正确的天数重试。",
                    MIN_DAYS, MAX_DAYS, days);
        }

        try {
            List<DailyForecast> forecasts = weather.forecast(keyword, days);
            if (forecasts.isEmpty()) {
                return "天气服务没有返回预报数据，请稍后再试。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s 未来 %d 天天气预报：", keyword, forecasts.size()));
            for (DailyForecast day : forecasts) {
                sb.append(String.format(Locale.ROOT, "%n- %s：%s，最高 %.1f ℃ / 最低 %.1f ℃",
                        day.date(), day.condition(), day.maxC(), day.minC()));
            }
            sb.append(String.format("%n数据来源：%s", weather.sourceName()));
            return sb.toString();
        } catch (CityNotFoundException e) {
            return cityNotFound(city);
        } catch (WeatherUnavailableException e) {
            log.warn("天气预报查询失败, city={}, days={}", keyword, days, e);
            return "天气服务暂时不可用，请稍后再试。";
        } catch (RuntimeException e) {
            log.error("天气预报查询异常, city={}, days={}", keyword, days, e);
            return "查询天气时发生异常，请稍后再试。";
        }
    }

    private static String normalizeCity(String city) {
        if (city == null) {
            return null;
        }
        String trimmed = city.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String cityNotFound(String city) {
        return String.format("没有找到城市「%s」。请换一个更常见的写法，例如「杭州」「Hangzhou」或「San Francisco」。", city);
    }
}
