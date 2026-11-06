package com.advisory.chat.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对着真实 MCP 服务跑一次握手：只有设了 {@code WEATHER_MCP_URL} 才执行。
 *
 * <p>默认不跑是刻意的——普通构建不该依赖「本机碰巧起着 weather-mcp」。要验证时：</p>
 * <pre>
 * docker compose up -d weather-mcp
 * WEATHER_MCP_URL=http://localhost:21001/sse mvn -f backend/advisory-server/pom.xml test -Dtest=McpToolRegistryLiveTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WEATHER_MCP_URL", matches = ".+")
class McpToolRegistryLiveTest {

    @Test
    void listsWeatherToolsFromRealServer() throws InterruptedException {
        McpToolRegistry registry = new McpToolRegistry(McpProperties.single("weather",
                System.getenv("WEATHER_MCP_URL"), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        try {
            registry.start();
            long deadline = System.currentTimeMillis() + 20_000;
            while (!registry.available() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }

            assertTrue(registry.available(), "未能连上 " + System.getenv("WEATHER_MCP_URL"));
            List<String> names = registry.toolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();
            assertTrue(names.contains("get_current_weather"), "实际工具：" + names);
            assertTrue(names.contains("get_weather_forecast"), "实际工具：" + names);

            // 真正走一次 tools/call：验证回调参数序列化与 SSE 往返，而不只是握手成功。
            ToolCallback currentWeather = registry.toolCallbacks().stream()
                    .filter(callback -> "get_current_weather".equals(callback.getToolDefinition().name()))
                    .findFirst()
                    .orElseThrow();
            String output = currentWeather.call("{\"city\":\"北京\"}");
            assertTrue(output.contains("当前天气"), "实际输出：" + output);
        } finally {
            registry.shutdown();
        }
    }
}
