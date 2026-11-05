package com.advisory.mcp.weather.client;

import com.advisory.mcp.weather.config.WeatherProperties;
import com.advisory.mcp.weather.model.DailyForecast;
import com.advisory.mcp.weather.model.GeoLocation;
import com.advisory.mcp.weather.model.WeatherNow;
import com.advisory.mcp.weather.support.WeatherCodes;
import com.advisory.mcp.weather.support.WeatherUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Open-Meteo 客户端。两个 API（geocoding / forecast）免费且无需 Key，因此这里只关心超时。
 *
 * 解析用 JsonNode 而不是映射成 record：上游字段是 snake_case，且返回哪些字段取决于请求参数，
 * 手写映射类只会换来一堆 @JsonProperty。缺失字段用 path() 拿到「缺失节点」，
 * 由调用方判断上游到底有没有给数据。
 */
@Component
public class OpenMeteoClient {

    private static final String CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m";
    private static final String DAILY_FIELDS = "weather_code,temperature_2m_max,temperature_2m_min";

    private final RestClient geocoding;
    private final RestClient forecast;

    public OpenMeteoClient(WeatherProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.timeout().toMillis());
        requestFactory.setReadTimeout((int) properties.timeout().toMillis());
        this.geocoding = builder.clone()
                .baseUrl(properties.geocodingBaseUrl())
                .requestFactory(requestFactory)
                .build();
        this.forecast = builder.clone()
                .baseUrl(properties.forecastBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public List<GeoLocation> geocode(String city) {
        JsonNode root;
        try {
            root = geocoding.get()
                    .uri(uri -> uri.path("/v1/search")
                            .queryParam("name", city)
                            .queryParam("count", 1)
                            .queryParam("language", "zh")
                            .queryParam("format", "json")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new WeatherUnavailableException("地理编码服务调用失败：" + e.getMessage(), e);
        }

        JsonNode results = root == null ? null : root.path("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return List.of();
        }

        JsonNode hit = results.get(0);
        return List.of(new GeoLocation(
                city,
                hit.path("name").asText(city),
                hit.path("admin1").asText(""),
                hit.path("country").asText(""),
                hit.path("latitude").asDouble(),
                hit.path("longitude").asDouble()));
    }

    public WeatherNow current(GeoLocation location) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("current", CURRENT_FIELDS);
        JsonNode root = getForecast(location, 1, params);

        JsonNode current = root.path("current");
        if (current.isMissingNode() || current.isEmpty()) {
            throw new WeatherUnavailableException("上游未返回实况数据", null);
        }
        return new WeatherNow(
                location,
                current.path("temperature_2m").asDouble(),
                current.path("apparent_temperature").asDouble(),
                current.path("relative_humidity_2m").asDouble(),
                current.path("wind_speed_10m").asDouble(),
                WeatherCodes.describe(current.path("weather_code").asInt()),
                current.path("time").asText());
    }

    public List<DailyForecast> forecast(GeoLocation location, int days) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("daily", DAILY_FIELDS);
        JsonNode root = getForecast(location, days, params);

        JsonNode daily = root.path("daily");
        JsonNode dates = daily.path("time");
        if (!dates.isArray() || dates.isEmpty()) {
            throw new WeatherUnavailableException("上游未返回预报数据", null);
        }

        List<DailyForecast> result = new ArrayList<>(dates.size());
        for (int i = 0; i < dates.size() && i < days; i++) {
            result.add(new DailyForecast(
                    dates.get(i).asText(),
                    WeatherCodes.describe(daily.path("weather_code").path(i).asInt()),
                    daily.path("temperature_2m_max").path(i).asDouble(),
                    daily.path("temperature_2m_min").path(i).asDouble()));
        }
        return result;
    }

    private JsonNode getForecast(GeoLocation location, int days, Map<String, Object> extraParams) {
        JsonNode root;
        try {
            root = forecast.get()
                    .uri(uri -> {
                        uri.path("/v1/forecast")
                                .queryParam("latitude", location.latitude())
                                .queryParam("longitude", location.longitude())
                                .queryParam("timezone", "auto")
                                .queryParam("forecast_days", days);
                        extraParams.forEach(uri::queryParam);
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new WeatherUnavailableException("天气服务调用失败：" + e.getMessage(), e);
        }
        if (root == null) {
            throw new WeatherUnavailableException("天气服务返回空响应", null);
        }
        return root;
    }
}
