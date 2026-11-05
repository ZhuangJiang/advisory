package com.advisory.chat.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具注册表的两条底线：关掉时完全空转；连不上时安静降级，绝不把异常带进问答链路。
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
        McpToolRegistry registry = new McpToolRegistry(new McpProperties(
                true, "http://127.0.0.1:1/sse", Duration.ofMillis(800), Duration.ofSeconds(30)));

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
        McpToolRegistry registry = new McpToolRegistry(new McpProperties(
                true, "", Duration.ofMillis(500), Duration.ofSeconds(30)));

        assertDoesNotThrow(registry::refresh);
        assertEquals(0, registry.toolCallbacks().size());
        registry.shutdown();
    }
}
