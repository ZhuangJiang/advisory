package com.advisory.chat.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * MCP 工具服务配置。
 *
 * @param enabled        总开关。关闭时编排器完全不知道工具存在，行为与接入前一致
 * @param requestTimeout 单次 MCP 请求超时；必须明显小于编排器单步 60 秒的预算，
 *                       否则工具卡住会连累整步超时，降级路径变成静默失败
 * @param retryInterval  后台重连间隔。工具服务不可用不能影响问答，因此连接放在后台线程重试
 * @param servers        工具服务清单。一个服务一条，各自独立连接、独立重试；
 *                       加一个 MCP 服务只改这里，不动代码
 */
@ConfigurationProperties("app.mcp")
public record McpProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20s") Duration requestTimeout,
        @DefaultValue("30s") Duration retryInterval,
        @DefaultValue List<Server> servers) {

    /**
     * 一个 MCP 工具服务。
     *
     * @param name   服务别名，用于日志与工具重名时的前缀（如 weather / news）
     * @param sseUrl SSE 端点，形如 {@code http://host:21001/sse}
     */
    public record Server(String name, String sseUrl) {

        public Server {
            name = name == null || name.isBlank() ? "mcp" : name.trim();
            sseUrl = sseUrl == null ? "" : sseUrl.trim();
        }
    }

    /** 单元测试与「没有 MCP」的装配用：永不连接。 */
    public static McpProperties disabled() {
        return new McpProperties(false, Duration.ofSeconds(1), Duration.ofSeconds(30), List.of());
    }

    /** 测试便捷：只接一个服务。 */
    public static McpProperties single(String name, String sseUrl, Duration requestTimeout, Duration retryInterval) {
        return new McpProperties(true, requestTimeout, retryInterval, List.of(new Server(name, sseUrl)));
    }
}
