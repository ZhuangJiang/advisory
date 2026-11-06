package com.advisory.chat.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具注册表的三条底线：关掉时完全空转；连不上时安静降级，绝不把异常带进问答链路；
 * 多服务聚合时重名工具必须被改名，否则模型分不清该调哪个。
 */
class McpToolRegistryTest {

    @Test
    void disabledRegistryIsInert() {
        McpToolRegistry registry = new McpToolRegistry(McpProperties.disabled());

        registry.start();

        assertFalse(registry.available());
        assertTrue(registry.toolCallbacks().isEmpty());
        registry.shutdown();
    }

    /**
     * 端口 1 上不会有服务：连接尝试必须快速失败、被吞掉，并保持「没有工具」的状态。
     * 这条用例同时守着「刷新线程抛异常导致调度被取消」这个坑——refresh 自己不抛。
     */
    @Test
    void unreachableServerDegradesQuietly() {
        McpToolRegistry registry = new McpToolRegistry(McpProperties.single(
                "weather", "http://127.0.0.1:1/sse", Duration.ofMillis(800), Duration.ofSeconds(30)));

        long startedAt = System.nanoTime();
        assertDoesNotThrow(registry::refresh);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertFalse(registry.available());
        assertTrue(registry.toolCallbacks().isEmpty());
        assertTrue(elapsedMs < 5_000, "连接失败不该拖住调用方，实际耗时 " + elapsedMs + "ms");

        // 失效处理也要能重复调用而不抛异常（网关在工具失败时会调它）
        assertDoesNotThrow(registry::invalidate);
        registry.shutdown();
    }

    @Test
    void emptyUrlIsReportedInsteadOfThrowing() {
        McpToolRegistry registry = new McpToolRegistry(McpProperties.single(
                "weather", "", Duration.ofMillis(500), Duration.ofSeconds(30)));

        assertDoesNotThrow(registry::refresh);
        assertEquals(0, registry.toolCallbacks().size());
        registry.shutdown();
    }

    /** 一个服务连不上不该拖累其他服务：聚合结果只来自连上的那些。 */
    @Test
    void oneUnreachableServerDoesNotBlockOthers() {
        McpToolRegistry registry = new McpToolRegistry(new McpProperties(
                true, Duration.ofMillis(600), Duration.ofSeconds(30), List.of(
                new McpProperties.Server("weather", "http://127.0.0.1:1/sse"),
                new McpProperties.Server("news", ""))));

        assertDoesNotThrow(registry::refresh);

        assertFalse(registry.available());
        assertTrue(registry.connectedServers().isEmpty());
        registry.shutdown();
    }

    @Test
    void uniqueToolNamesAreKeptAsIs() {
        Map<String, List<ToolCallback>> byServer = new LinkedHashMap<>();
        byServer.put("weather", List.of(tool("get_current_weather")));
        byServer.put("news", List.of(tool("search_news")));

        List<String> names = McpToolRegistry.aggregate(byServer).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertEquals(List.of("get_current_weather", "search_news"), names);
    }

    @Test
    void duplicateToolNamesArePrefixedWithServerName() {
        Map<String, List<ToolCallback>> byServer = new LinkedHashMap<>();
        byServer.put("weather", List.of(tool("search")));
        byServer.put("news", List.of(tool("search"), tool("get_headlines")));

        List<String> names = McpToolRegistry.aggregate(byServer).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertEquals(List.of("weather_search", "news_search", "get_headlines"), names);
    }

    @Test
    void renamedCallbackStillDelegatesTheCall() {
        ToolCallback original = tool("search");
        RenamedToolCallback renamed = new RenamedToolCallback(original, "news_search");

        assertEquals("news_search", renamed.getToolDefinition().name());
        assertEquals("ok", renamed.call("{}"));
    }

    /** 最小可用假回调：定义可读，调用返回固定文本。 */
    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("test tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }
}
