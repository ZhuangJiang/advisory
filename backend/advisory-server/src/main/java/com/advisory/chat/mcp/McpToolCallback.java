package com.advisory.chat.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 把一个 MCP 工具包成 Spring AI 的 {@link ToolCallback}。
 *
 * <p>为什么不直接用 {@code SyncMcpToolCallback}：它构造时会给工具名加客户端前缀
 * （实测为 {@code JavaSDKMCPClient_get_current_weather}），这个前缀会原样进入模型上下文、
 * 推理步骤摘要与日志。单服务场景下服务端原名更清楚；将来真的接入第二个 MCP 服务、
 * 出现重名工具时，再在这里按服务别名加前缀。</p>
 *
 * <p>错误处理：MCP 返回 {@code isError=true} 时抛异常，而不是把错误文本当正常结果喂给模型。
 * 抛出的异常会被 Spring AI 包成 {@code ToolExecutionException}，网关据此断开连接，
 * 编排器回退到知识库检索——比让模型对着「查询失败」自由发挥可靠。</p>
 */
final class McpToolCallback implements ToolCallback {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {
    };

    private final McpSyncClient client;
    private final McpSchema.Tool tool;
    private final ToolDefinition definition;

    McpToolCallback(McpSyncClient client, McpSchema.Tool tool) {
        this.client = Objects.requireNonNull(client, "client");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.definition = ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description() == null ? "" : tool.description())
                .inputSchema(writeSchema(tool.inputSchema()))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> arguments = readArguments(toolInput);
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(tool.name(), arguments));

        String text = result.content() == null ? "" : result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining("\n"));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP 工具 " + tool.name() + " 执行失败：" + text);
        }
        return text;
    }

    private static Map<String, Object> readArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(toolInput, ARGUMENTS);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON：" + e.getOriginalMessage(), e);
        }
    }

    private static String writeSchema(Object schema) {
        try {
            // McpSchema.JsonSchema 是 record，Jackson 按组件名序列化即可得到标准 JSON Schema
            return JSON.writeValueAsString(schema == null
                    ? Map.of("type", "object", "properties", Map.of())
                    : schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 MCP 工具的参数定义", e);
        }
    }
}
