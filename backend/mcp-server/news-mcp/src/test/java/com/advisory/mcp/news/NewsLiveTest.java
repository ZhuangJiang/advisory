package com.advisory.mcp.news;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真源联调：不设桩、真的去抓配置里的 RSS 源。
 *
 * <p>默认跳过（{@code NEWS_LIVE_TEST=true} 才跑），因为它依赖公网、且结果随时间变化——
 * 放进常规测试会让构建在断网或某个源临时抽风时变红。它要证明的是「源清单还有效、
 * 解析还能吃下真实 XML」，这类问题只有打真网络才会暴露。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "NEWS_LIVE_TEST", matches = "true")
class NewsLiveTest {

    @LocalServerPort
    private int port;

    private McpSyncClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void returnsRealHeadlinesFromConfiguredFeeds() {
        client = McpClient.sync(HttpClientSseClientTransport
                        .builder("http://localhost:" + port)
                        .sseEndpoint("/sse")
                        .build())
                .build();
        client.initialize();

        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest("get_headlines", Map.of("category", "科技", "limit", 3)));

        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .reduce("", String::concat);

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text)
                .doesNotContain("新闻源暂时不可用")
                .contains("科技头条")
                .contains("链接：http");
        System.out.println("[NEWS-LIVE] " + text);
    }

    @Test
    void findsKeywordAcrossRealFeeds() {
        client = McpClient.sync(HttpClientSseClientTransport
                        .builder("http://localhost:" + port)
                        .sseEndpoint("/sse")
                        .build())
                .build();
        client.initialize();

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("search_news", Map.of("keyword", "新能源", "limit", 3)));

        System.out.println("[NEWS-SEARCH] " + text(result));
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .reduce("", String::concat);
    }
}
