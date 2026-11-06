package com.advisory.chat.mcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 给工具改名的薄包装。只在「两个 MCP 服务暴露了同名工具」时使用：
 * 名字冲突时模型无法区分该调哪一个，加服务名前缀后语义明确。
 *
 * <p>只改对外的 {@link ToolDefinition}，调用仍然交给原回调——MCP 请求里的工具名
 * 必须保持服务端原名，否则服务端会说不认识这个工具。</p>
 */
final class RenamedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition definition;

    RenamedToolCallback(ToolCallback delegate, String name) {
        this.delegate = delegate;
        ToolDefinition original = delegate.getToolDefinition();
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(original.description() == null ? "" : original.description())
                .inputSchema(original.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }
}
