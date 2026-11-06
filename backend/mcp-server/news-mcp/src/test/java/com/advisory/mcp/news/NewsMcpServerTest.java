package com.advisory.mcp.news;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真容器 + 真 SSE 的协议测：起 Spring Boot（随机端口），用 MCP SDK 客户端走
 * GET /sse → initialize → tools/list → tools/call。与 weather-mcp 的同名测试同构，
 * MockMvc 覆盖不到 SSE 的握手与事件帧。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.news.provider=stub")
class NewsMcpServerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private McpSyncClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    private McpSyncClient connect() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://localhost:" + port)
                .sseEndpoint("/sse")
                .build();
        client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    @Test
    void exposesHealthEndpointForContainerProbe() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void exposesNewsToolsToMcpClients() {
        McpSyncClient mcp = connect();

        List<String> names = mcp.listTools().tools().stream().map(McpSchema.Tool::name).toList();

        assertThat(names).contains("search_news", "get_headlines");
    }

    @Test
    void answersKeywordSearch() {
        McpSyncClient mcp = connect();

        McpSchema.CallToolResult result = mcp.callTool(
                new McpSchema.CallToolRequest("search_news", Map.of("keyword", "新能源", "limit", 3)));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result))
                .contains("找到")
                .contains("新能源")
                .contains("数据来源：Stub");
    }

    @Test
    void answersHeadlinesRequest() {
        McpSyncClient mcp = connect();

        McpSchema.CallToolResult result = mcp.callTool(
                new McpSchema.CallToolRequest("get_headlines", Map.of("category", "科技", "limit", 2)));

        assertThat(text(result)).contains("科技头条").contains("来源：Stub 新闻");
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .reduce("", String::concat);
    }
}
