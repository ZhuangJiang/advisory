package com.advisory.chat.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * MCP 工具服务配置。
 *
 * @param enabled        总开关。关闭时编排器完全不知道工具存在，行为与接入前一致
 * @param sseUrl         工具的 SSE 端点（weather-mcp 为 {@code /sse}）
 * @param requestTimeout 单次 MCP 请求超时；必须明显小于编排器单步 60 秒的预算，
 *                       否则工具卡住会连累整步超时，降级路径变成静默失败
 * @param retryInterval  后台重连间隔。工具服务不可用不能影响问答，因此连接放在后台线程重试
 */
@ConfigurationProperties("app.mcp")
public record McpProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:8081/sse") String sseUrl,
        @DefaultValue("20s") Duration requestTimeout,
        @DefaultValue("30s") Duration retryInterval) {

    /** 单元测试与「没有 MCP」的装配用：永不连接。 */
    public static McpProperties disabled() {
        return new McpProperties(false, "", Duration.ofSeconds(1), Duration.ofSeconds(30));
    }
}
