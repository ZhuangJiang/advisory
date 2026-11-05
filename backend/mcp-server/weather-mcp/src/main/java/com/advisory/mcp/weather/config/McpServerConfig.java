package com.advisory.mcp.weather.config;

import com.advisory.mcp.weather.tool.WeatherTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link WeatherTools} 里的 @Tool 方法注册成 MCP 工具。
 * 只要容器里存在 ToolCallbackProvider，MCP Server 启动时就会自动把它们出现在 tools/list 里，
 * 不需要额外的扫描开关。
 */
@Configuration
public class McpServerConfig {

    @Bean
    ToolCallbackProvider weatherToolCallbackProvider(WeatherTools weatherTools) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherTools).build();
    }
}
