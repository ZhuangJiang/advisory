package com.advisory.mcp.news.config;

import com.advisory.mcp.news.tool.NewsTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link NewsTools} 里的 @Tool 方法注册成 MCP 工具。
 * 容器里存在 ToolCallbackProvider，MCP Server 启动时就会把它们暴露到 tools/list。
 */
@Configuration
public class McpServerConfig {

    @Bean
    ToolCallbackProvider newsToolCallbackProvider(NewsTools newsTools) {
        return MethodToolCallbackProvider.builder().toolObjects(newsTools).build();
    }
}
