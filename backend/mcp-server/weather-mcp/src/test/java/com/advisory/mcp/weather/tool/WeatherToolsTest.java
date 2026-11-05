package com.advisory.mcp.weather.tool;

import com.advisory.mcp.weather.model.DailyForecast;
import com.advisory.mcp.weather.model.GeoLocation;
import com.advisory.mcp.weather.model.WeatherNow;
import com.advisory.mcp.weather.service.WeatherService;
import com.advisory.mcp.weather.support.CityNotFoundException;
import com.advisory.mcp.weather.support.WeatherUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具层的契约：永远返回可读文本，不抛异常；参数不合法时不打扰上游。
 * 文案本身也是契约——模型会照抄，所以断言里带上关键片段。
 */
@ExtendWith(MockitoExtension.class)
class WeatherToolsTest {

    @Mock
    private WeatherService weather;

    private WeatherTools tools;

    @BeforeEach
    void setUp() {
        tools = new WeatherTools(weather);
    }

    @Test
    void formatsCurrentWeather() {
        GeoLocation beijing = new GeoLocation("北京", "北京", "北京市", "中国", 39.9, 116.4);
        when(weather.current("北京")).thenReturn(
                new WeatherNow(beijing, 12.34, 10.8, 45, 8.4, "晴", "2026-11-05T08:00"));
        when(weather.sourceName()).thenReturn("Open-Meteo");

        String result = tools.getCurrentWeather("北京");

        assertThat(result)
                .contains("北京（北京市，中国） 当前天气")
                .contains("天气：晴")
                .contains("气温：12.3 ℃（体感 10.8 ℃）")
                .contains("相对湿度：45%")
                .contains("风速：8.4 km/h")
                .contains("观测时间：2026-11-05T08:00")
                .contains("数据来源：Open-Meteo");
    }

    @Test
    void trimsCityBeforeQuerying() {
        GeoLocation hangzhou = new GeoLocation("杭州", "杭州", "浙江省", "中国", 30.3, 120.2);
        when(weather.current("杭州")).thenReturn(
                new WeatherNow(hangzhou, 20, 20, 60, 5, "多云", "2026-11-05T08:00"));

        assertThat(tools.getCurrentWeather("  杭州  ")).contains("杭州");

        verify(weather).current("杭州");
    }

    @Test
    void asksForCityWhenBlank() {
        assertThat(tools.getCurrentWeather("   ")).contains("请提供城市名");
        assertThat(tools.getCurrentWeather(null)).contains("请提供城市名");

        verify(weather, never()).current(anyString());
    }

    @Test
    void rejectsOutOfRangeDaysWithoutCallingUpstream() {
        assertThat(tools.getWeatherForecast("北京", 0)).contains("1 到 7");
        assertThat(tools.getWeatherForecast("北京", 8)).contains("1 到 7");
        assertThat(tools.getWeatherForecast("北京", -3)).contains("1 到 7");

        verify(weather, never()).forecast(anyString(), anyInt());
    }

    @Test
    void formatsForecast() {
        when(weather.forecast("北京", 2)).thenReturn(List.of(
                new DailyForecast("2026-11-05", "晴", 15.24, 3.1),
                new DailyForecast("2026-11-06", "小雨", 12.0, 5.5)));
        when(weather.sourceName()).thenReturn("Stub");

        String result = tools.getWeatherForecast("北京", 2);

        assertThat(result)
                .contains("北京 未来 2 天天气预报")
                .contains("- 2026-11-05：晴，最高 15.2 ℃ / 最低 3.1 ℃")
                .contains("- 2026-11-06：小雨，最高 12.0 ℃ / 最低 5.5 ℃")
                .contains("数据来源：Stub");
    }

    @Test
    void turnsUnknownCityIntoAdvice() {
        when(weather.current("火星")).thenThrow(new CityNotFoundException("火星"));

        assertThat(tools.getCurrentWeather("火星"))
                .contains("没有找到城市「火星」")
                .contains("杭州");
    }

    @Test
    void turnsUpstreamFailureIntoPlainText() {
        when(weather.forecast("北京", 3))
                .thenThrow(new WeatherUnavailableException("connect timeout", null));

        assertThat(tools.getWeatherForecast("北京", 3))
                .isEqualTo("天气服务暂时不可用，请稍后再试。");
    }

    @Test
    void neverLeaksUnexpectedException() {
        when(weather.current("北京")).thenThrow(new IllegalStateException("boom"));

        assertThat(tools.getCurrentWeather("北京")).isEqualTo("查询天气时发生异常，请稍后再试。");
    }
}
