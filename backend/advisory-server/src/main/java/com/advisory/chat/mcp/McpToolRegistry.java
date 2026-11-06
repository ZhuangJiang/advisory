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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 外部 MCP 工具服务的连接管理（多服务版）。
 *
 * <p><b>为什么不在启动时同步连接</b>：MCP 服务是独立部署的，它没起来、重启中、网络抖动都不该拦住
 * 主服务启动，更不该让提问失败。因此连接交给后台线程：启动后立即尝试一次，失败就按
 * {@code retryInterval} 一直重试；对话路径只读内存里的工具列表，永不阻塞。</p>
 *
 * <p><b>多服务是「每个服务一条连接」</b>：某个服务挂了只影响它自己的工具，
 * 其他服务的工具照常可用——这是「天气 MCP 停掉，新闻照问」的前提。工具列表为空不代表
 * 全部不可用，编排器只看「有没有任何工具」。</p>
 *
 * <p><b>工具重名</b>：不同服务可能暴露同名工具（如两个服务都叫 search）。聚合时检测，
 * 冲突的工具名加上服务别名前缀（{@code news_search}），并打警告——重名工具让模型无法
 * 判断该调哪一个，比名字难看严重得多。</p>
 */
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private record Connection(McpSyncClient client, List<ToolCallback> callbacks) {
    }

    private final McpProperties properties;
    private final Object monitor = new Object();
    private final Map<String, Connection> connections = new LinkedHashMap<>();

    private volatile ScheduledExecutorService refresher;
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

    /** 已连接的服务名，供日志与诊断用。 */
    public Set<String> connectedServers() {
        synchronized (monitor) {
            return new HashSet<>(connections.keySet());
        }
    }

    /**
     * 工具调用失败后由网关调用：丢掉全部连接，等后台线程重连。
     *
     * <p>刻意不做「只重连失败的那一个」：网关抛异常时只拿得到工具名，而重名的可能性
     * 恰恰说明工具名不足以唯一定位服务。工具执行失败本身很少见，全量重连的代价可以接受，
     * 换来的是不会误把健康连接关掉再重连一整轮。</p>
     */
    public void invalidate() {
        synchronized (monitor) {
            connections.values().forEach(connection -> closeQuietly(connection.client()));
            connections.clear();
            toolCallbacks = List.of();
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
            connections.values().forEach(connection -> closeQuietly(connection.client()));
            connections.clear();
            toolCallbacks = List.of();
        }
    }

    /** 包可见：测试直接驱动一次连接尝试，不必等后台线程的调度周期。 */
    void refresh() {
        for (McpProperties.Server server : properties.servers()) {
            if (server.sseUrl().isBlank()) {
                log.warn("MCP 服务 [{}] 没有配置 sse-url，已跳过", server.name());
                continue;
            }
            synchronized (monitor) {
                if (connections.containsKey(server.name())) {
                    continue;
                }
            }
            try {
                connect(server);
            } catch (RuntimeException e) {
                // 不打印堆栈：本机没起 MCP 服务是最常见的情况，每次重试都刷一屏栈没有信息量。
                log.warn("MCP 工具服务 [{}] {} 不可用：{}；{} 秒后重试",
                        server.name(), server.sseUrl(), rootMessage(e), properties.retryInterval().toSeconds());
            }
        }
        synchronized (monitor) {
            Map<String, List<ToolCallback>> byServer = new LinkedHashMap<>();
            connections.forEach((name, connection) -> byServer.put(name, connection.callbacks()));
            toolCallbacks = aggregate(byServer);
        }
    }

    private void connect(McpProperties.Server server) {
        McpSyncClient newClient = McpClient.sync(
                        HttpClientSseClientTransport.builder(server.sseUrl()).build())
                .requestTimeout(properties.requestTimeout())
                .build();
        try {
            newClient.initialize();
            // 刻意不用 SyncMcpToolCallbackProvider / SyncMcpToolCallback：它们会给工具名加
            // `JavaSDKMCPClient_` 之类的前缀（多客户端去重用的），这个前缀会原样进入模型上下文
            // 与推理步骤摘要。多服务下的重名问题由 aggregate() 按需加服务别名，命名可控。
            List<McpSchema.Tool> tools = newClient.listTools().tools();
            List<ToolCallback> callbacks = tools.stream()
                    .map(tool -> (ToolCallback) new McpToolCallback(newClient, tool))
                    .toList();
            if (callbacks.isEmpty()) {
                log.warn("MCP 服务 [{}] {} 已连接，但没有暴露任何工具", server.name(), server.sseUrl());
                closeQuietly(newClient);
                return;
            }
            synchronized (monitor) {
                connections.put(server.name(), new Connection(newClient, callbacks));
            }
            log.info("已连接 MCP 工具服务 [{}] {}，可用工具：{}", server.name(), server.sseUrl(),
                    callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
        } catch (RuntimeException e) {
            closeQuietly(newClient);
            throw e;
        }
    }

    /**
     * 把各服务的工具合成一个列表，并处理重名。
     *
     * <p>重名的判定必须是「跨服务」的：同一服务内部重名是服务端自己的问题，加前缀也解决不了。
     * 只有出现在两个不同服务里的同名工具才加前缀，并且双方都加——只给一方加依然分不清。</p>
     */
    static List<ToolCallback> aggregate(Map<String, List<ToolCallback>> byServer) {
        Map<String, Set<String>> owners = new HashMap<>();
        for (Map.Entry<String, List<ToolCallback>> entry : byServer.entrySet()) {
            for (ToolCallback callback : entry.getValue()) {
                owners.computeIfAbsent(callback.getToolDefinition().name(), key -> new HashSet<>())
                        .add(entry.getKey());
            }
        }

        List<ToolCallback> aggregated = new ArrayList<>();
        for (Map.Entry<String, List<ToolCallback>> entry : byServer.entrySet()) {
            String serverName = entry.getKey();
            for (ToolCallback callback : entry.getValue()) {
                String toolName = callback.getToolDefinition().name();
                boolean sharedAcrossServers = owners.getOrDefault(toolName, Set.of()).size() > 1;
                if (sharedAcrossServers) {
                    String prefixed = serverName + "_" + toolName;
                    log.warn("工具名 [{}] 在多个 MCP 服务中重复，已重命名为 [{}]", toolName, prefixed);
                    aggregated.add(new RenamedToolCallback(callback, prefixed));
                } else {
                    aggregated.add(callback);
                }
            }
        }
        return List.copyOf(aggregated);
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
