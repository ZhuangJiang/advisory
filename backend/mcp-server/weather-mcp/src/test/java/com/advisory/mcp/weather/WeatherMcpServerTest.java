package com.advisory.mcp.weather;

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
 * 真容器 + 真 SSE 的协议测：起 Spring Boot（随机端口），用 MCP SDK 的客户端走
 * GET /sse 建流 → initialize → tools/list → tools/call。
 *
 * 为什么不用 MockMvc：SSE 是异步长连接，MockMvc 的请求响应模型根本覆盖不到握手与事件帧——
 * 仓库里已经在这一类地方摔过一次（SseEmitter 完成时被安全链掐断，只在真容器暴露）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.weather.provider=stub")
class WeatherMcpServerTest {

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
    void exposesWeatherToolsToMcpClients() {
        McpSyncClient mcp = connect();

        List<String> names = mcp.listTools().tools().stream().map(McpSchema.Tool::name).toList();

        assertThat(names).contains("get_current_weather", "get_weather_forecast");
    }

    @Test
    void answersCurrentWeatherCall() {
        McpSyncClient mcp = connect();

        McpSchema.CallToolResult result = mcp.callTool(
                new McpSchema.CallToolRequest("get_current_weather", Map.of("city", "北京")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result))
                .contains("当前天气")
                .contains("数据来源：Stub");
    }

    @Test
    void answersForecastCallWithRequestedDayCount() {
        McpSyncClient mcp = connect();

        McpSchema.CallToolResult result = mcp.callTool(
                new McpSchema.CallToolRequest("get_weather_forecast", Map.of("city", "杭州", "days", 2)));

        assertThat(text(result))
                .contains("未来 2 天天气预报")
                .contains("最高")
                .contains("最低");
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .reduce("", String::concat);
    }
}
