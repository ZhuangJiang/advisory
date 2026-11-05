package com.advisory.chat.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 外部 MCP 工具服务的连接管理。
 *
 * <p><b>为什么不在启动时同步连接</b>：MCP 服务是独立部署的，它没起来、重启中、网络抖动都不该拦住
 * 主服务启动，更不该让提问失败。因此连接交给后台线程：启动后立即尝试一次，失败就按
 * {@code retryInterval} 一直重试；对话路径只读内存里的工具列表，永不阻塞。</p>
 *
 * <p><b>为什么工具列表为空就是「没有工具」</b>：编排器据此决定是否走工具分支，不需要额外开关。
 * 工具调用失败时网关会调用 {@link #invalidate()}，下一轮后台重试重新连接——半个死连接的客户端
 * 比没有客户端更糟，它会一直抛超时。</p>
 */
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final McpProperties properties;
    private final Object monitor = new Object();

    private volatile ScheduledExecutorService refresher;
    private volatile McpSyncClient client;
    private volatile List<ToolCallback> toolCallbacks = List.of();
    private volatile boolean started;

    public McpToolRegistry(McpProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** 启动后台连接线程。{@code enabled=false} 时什么都不做。 */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            return;
        }
        synchronized (monitor) {
            if (started) {
                return;
            }
            started = true;
            refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcp-tool-connect");
                thread.setDaemon(true);
                return thread;
            });
            long interval = Math.max(1_000L, properties.retryInterval().toMillis());
            refresher.scheduleWithFixedDelay(this::refresh, 0, interval, TimeUnit.MILLISECONDS);
        }
    }

    /** 是否有可用的外部工具。对话路径上每问一次都会调用，必须是纯内存操作。 */
    public boolean available() {
        return properties.enabled() && !toolCallbacks.isEmpty();
    }

    public List<ToolCallback> toolCallbacks() {
        return properties.enabled() ? toolCallbacks : List.of();
    }

    /** 工具调用失败后由网关调用：丢掉当前连接，等后台线程重连。 */
    public void invalidate() {
        synchronized (monitor) {
            toolCallbacks = List.of();
            closeClient();
        }
        log.warn("MCP 工具连接已失效，将在 {} 后重试", properties.retryInterval());
    }

    @PreDestroy
    public void shutdown() {
        ScheduledExecutorService current = refresher;
        if (current != null) {
            current.shutdownNow();
        }
        synchronized (monitor) {
            closeClient();
        }
    }

    /** 包可见：测试直接驱动一次连接尝试，不必等后台线程的调度周期。 */
    void refresh() {
        if (!toolCallbacks.isEmpty()) {
            return;
        }
        try {
            connect();
        } catch (RuntimeException e) {
            // 不打印堆栈：本机没起 weather-mcp 是最常见的情况，每次重试都刷一屏栈没有信息量。
            log.warn("MCP 工具服务不可用（{}）：{}；{} 秒后重试",
                    properties.sseUrl(), rootMessage(e), properties.retryInterval().toSeconds());
        }
    }

    private void connect() {
        synchronized (monitor) {
            if (!toolCallbacks.isEmpty()) {
                return;
            }
            closeClient();
            McpSyncClient newClient = McpClient.sync(
                            HttpClientSseClientTransport.builder(properties.sseUrl()).build())
                    .requestTimeout(properties.requestTimeout())
                    .build();
            try {
                newClient.initialize();
                // 刻意不用 SyncMcpToolCallbackProvider / SyncMcpToolCallback：它们会给工具名加
                // `JavaSDKMCPClient_` 之类的前缀（多客户端去重用的），这个前缀会原样进入模型上下文
                // 与推理步骤摘要。单服务场景下保持服务端原名更清楚。
                List<McpSchema.Tool> tools = newClient.listTools().tools();
                List<ToolCallback> callbacks = tools.stream()
                        .map(tool -> (ToolCallback) new McpToolCallback(newClient, tool))
                        .toList();
                if (callbacks.isEmpty()) {
                    log.warn("MCP 服务 {} 已连接，但没有暴露任何工具", properties.sseUrl());
                    closeQuietly(newClient);
                    return;
                }
                this.client = newClient;
                this.toolCallbacks = callbacks;
                log.info("已连接 MCP 工具服务 {}，可用工具：{}", properties.sseUrl(),
                        callbacks.stream().map(c -> c.getToolDefinition().name()).toList());
            } catch (RuntimeException e) {
                closeQuietly(newClient);
                throw e;
            }
        }
    }

    private void closeClient() {
        McpSyncClient current = this.client;
        this.client = null;
        closeQuietly(current);
    }

    private static void closeQuietly(McpSyncClient target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (RuntimeException e) {
            log.debug("关闭 MCP 连接时出错", e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
