package com.advisory.chat.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ollama 客户端的请求选项。
 *
 * <p>重点是 {@code num_ctx}：不传它，Ollama 就按服务端默认的 4096 分配窗口，检索证据累积两轮
 * 就会被 400 顶回来（{@code exceeds the available context size}）。这里断言配置值确实进了选项。
 */
class ChatClientFactoryTest {

    @Test
    void ollamaClientCarriesTheConfiguredContextWindow() {
        ChatClientFactory.ChatClient client = ChatClientFactory.buildOllamaClient(
                "http://localhost:11434", "qwen2.5:7b", 0.3, 8192);

        OllamaOptions options = (OllamaOptions) client.options();
        assertEquals(8192, options.getNumCtx());
        assertEquals("qwen2.5:7b", options.getModel());
        assertEquals(0.3, options.getTemperature(), 1e-9);
    }

    @Test
    void trailingSlashesInTheBaseUrlAreNormalised() {
        ChatClientFactory.ChatClient client = ChatClientFactory.buildOllamaClient(
                "http://localhost:11434///", "qwen2.5:7b", null, 4096);

        assertEquals(4096, ((OllamaOptions) client.options()).getNumCtx());
    }
}
