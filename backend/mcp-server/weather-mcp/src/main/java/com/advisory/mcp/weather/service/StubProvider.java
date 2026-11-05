package com.advisory.mcp.weather.service;

import com.advisory.mcp.weather.model.DailyForecast;
import com.advisory.mcp.weather.model.GeoLocation;
import com.advisory.mcp.weather.model.WeatherNow;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线桩数据：不访问公网，供本地开发与自动化测试使用（WEATHER_PROVIDER=stub）。
 * 数值由城市名散列决定，同一城市每次调用一致，断言才写得稳。
 */
@Component
public class StubProvider implements WeatherProvider {

    private static final List<String> NOW_CONDITIONS = List.of("晴", "多云", "阴", "小雨", "阵雨");
    private static final List<String> FORECAST_CONDITIONS = List.of("晴", "多云", "阴", "小雨");

    @Override
    public String sourceName() {
        return "Stub";
    }

    @Override
    public WeatherNow current(String city) {
        long seed = Math.abs((long) city.hashCode());
        double base = 8 + seed % 15;
        return new WeatherNow(
                location(city),
                round1(base + (seed % 10) / 10.0),
                round1(base - 1.5),
                40 + seed % 40,
                round1(5 + seed % 20),
                NOW_CONDITIONS.get((int) (seed % NOW_CONDITIONS.size())),
                LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).toString());
    }

    @Override
    public List<DailyForecast> forecast(String city, int days) {
        long seed = Math.abs((long) city.hashCode());
        double base = 8 + seed % 15;
        LocalDate today = LocalDate.now();
        DateTimeFormatter isoDate = DateTimeFormatter.ISO_LOCAL_DATE;

        List<DailyForecast> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            double max = base + i % 3;
            result.add(new DailyForecast(
                    today.plusDays(i).format(isoDate),
                    FORECAST_CONDITIONS.get((int) ((seed + i) % FORECAST_CONDITIONS.size())),
                    round1(max),
                    round1(max - 7)));
        }
        return result;
    }

    private GeoLocation location(String city) {
        return new GeoLocation(city, city, "Stub", "Stub", 0, 0);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
