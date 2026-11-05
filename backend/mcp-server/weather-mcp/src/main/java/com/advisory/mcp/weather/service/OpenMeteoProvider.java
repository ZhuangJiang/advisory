package com.advisory.mcp.weather.service;

import com.advisory.mcp.weather.client.OpenMeteoClient;
import com.advisory.mcp.weather.config.WeatherProperties;
import com.advisory.mcp.weather.model.DailyForecast;
import com.advisory.mcp.weather.model.GeoLocation;
import com.advisory.mcp.weather.model.WeatherNow;
import com.advisory.mcp.weather.support.CityNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open-Meteo 数据源实现，顺带缓存「城市 → 经纬度」。
 *
 * 缓存放在这里而不是工具层：一次预报查询只做一次地理编码，但同一城市反复被问时
 * 地理编码请求就是纯浪费。地理坐标几乎不变，TTL 默认 1 小时足够。
 */
@Component
public class OpenMeteoProvider implements WeatherProvider {

    private static final int MAX_CACHE_ENTRIES = 500;

    private record CachedLocation(GeoLocation location, Instant expiresAt) {
    }

    private final OpenMeteoClient client;
    private final Duration cacheTtl;
    private final ConcurrentHashMap<String, CachedLocation> cache = new ConcurrentHashMap<>();

    public OpenMeteoProvider(OpenMeteoClient client, WeatherProperties properties) {
        this.client = client;
        this.cacheTtl = properties.geocodingCacheTtl();
    }

    @Override
    public String sourceName() {
        return "Open-Meteo";
    }

    @Override
    public WeatherNow current(String city) {
        return client.current(resolve(city));
    }

    @Override
    public List<DailyForecast> forecast(String city, int days) {
        return client.forecast(resolve(city), days);
    }

    private GeoLocation resolve(String city) {
        String keyword = city.trim();
        String key = keyword.toLowerCase(Locale.ROOT);
        Instant now = Instant.now();

        CachedLocation cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.location();
        }

        List<GeoLocation> hits = client.geocode(keyword);
        if (hits.isEmpty()) {
            throw new CityNotFoundException(city);
        }

        GeoLocation location = hits.get(0);
        // 简单上限：长期运行下城市名集合不可控，宁可清空也不要无界增长
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new CachedLocation(location, now.plus(cacheTtl)));
        return location;
    }
}
