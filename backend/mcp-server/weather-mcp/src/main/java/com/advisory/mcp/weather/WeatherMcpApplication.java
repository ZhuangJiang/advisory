package com.advisory.mcp.weather;

import com.advisory.mcp.weather.config.WeatherProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WeatherProperties.class)
public class WeatherMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherMcpApplication.class, args);
    }
}
