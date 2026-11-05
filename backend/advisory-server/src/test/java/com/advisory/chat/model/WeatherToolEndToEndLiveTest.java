package com.advisory.chat.model;

import com.advisory.chat.mcp.McpProperties;
import com.advisory.chat.mcp.McpToolRegistry;
import com.advisory.chat.orchestration.PromptTemplates;
import com.advisory.common.error.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真模型 + 真 MCP 服务的最小闭环：工具下发、模型是否调用、失败如何表现。
 *
 * <p>两个环境变量同时存在才执行：</p>
 * <pre>
 * docker compose up -d weather-mcp
 * WEATHER_MCP_URL=http://localhost:21001/sse \
 * OLLAMA_E2E_URL=http://172.25.32.90:11434 OLLAMA_E2E_MODEL=deepseek-r1:7b \
 * mvn -f backend/advisory-server/pom.xml test -Dtest=WeatherToolEndToEndLiveTest
 * </pre>
 *
 * <p>断言的是<b>不会出现编造的天气答案</b>：模型支持 function calling 时答案里要有工具给出的温度；
 * 不支持时（例如 deepseek-r1 接受 tools 参数却从不调用）必须被拦成异常，让编排器回退到检索。</p>
 */
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "WEATHER_MCP_URL", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "OLLAMA_E2E_URL", matches = ".+")
})
class WeatherToolEndToEndLiveTest {

    @Test
    void realModelEitherCallsTheToolOrIsRejected() throws InterruptedException {
        String weatherUrl = System.getenv("WEATHER_MCP_URL");
        String ollamaUrl = System.getenv("OLLAMA_E2E_URL");
        String modelName = System.getenv().getOrDefault("OLLAMA_E2E_MODEL", "qwen2.5:7b");

        McpToolRegistry registry = new McpToolRegistry(
                new McpProperties(true, weatherUrl, Duration.ofSeconds(20), Duration.ofSeconds(5)));
        registry.start();
        try {
            long deadline = System.currentTimeMillis() + 20_000;
            while (!registry.available() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(registry.available(), "未能连上 " + weatherUrl);

            ChatClientFactory.ChatClient client =
                    ChatClientFactory.buildOllamaClient(ollamaUrl, modelName, 0.0, 4096);
            ChatClientFactory factory = new ChatClientFactory(null, null) {
                @Override
                public ChatClient forChat(long chatModelId, BigDecimal temperature) {
                    return client;
                }
            };
            SpringAiChatModelGateway gateway = new SpringAiChatModelGateway(factory, registry);

            try {
                String answer = gateway.chatWithTools(1L, null,
                        PromptTemplates.toolSystem("测试助手"), "北京今天天气怎么样？");
                assertTrue(answer.contains("℃") || answer.contains("°C"),
                        "模型声称用了工具，答案里就应该有工具返回的温度，实际：" + answer);
            } catch (AppException e) {
                assertTrue(e.getMessage().contains("没有调用任何工具"),
                        "模型不支持 function calling 时必须被拦下，实际：" + e.getMessage());
            }
        } finally {
            registry.shutdown();
        }
    }
}
