package com.advisory.support;

import com.advisory.chat.orchestration.ChatModelGateway;
import com.advisory.chat.orchestration.PlanResult;
import com.advisory.chat.orchestration.ReflectResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.util.List;

/**
 * 供每个 {@code @SpringBootTest} 上下文使用的确定性 {@link ChatModelGateway}。
 *
 * <p>与 {@link TestEmbeddingGatewayConfig} 对称：{@link IntegrationTestBase} 把
 * {@code app.chat.gateway} 设为 {@code stub}，真实的 Spring AI 网关（见 {@code ChatConfig}）
 * 因此不会被注册，任何用例都不可能误调提供商。桩的行为固定：永远只规划一条查询、
 * 第一轮就报「证据充分」、然后直接作答——于是编排完全确定：每个问题一次 PLAN、一次 RETRIEVE、
 * 一次 REFLECT、一次 ANSWER。</p>
 *
 * <p>需要不同模型行为的用例可用 {@code @MockitoBean ChatModelGateway} 替换它。</p>
 */
@Configuration(proxyBeanMethods = false)
public class TestChatModelGatewayConfig {

    /** 桩给出的回答内容；用例据此断言流里确实带出了真实答案。 */
    public static final String STUB_ANSWER = "根据知识库片段，答案是 [1]。";

    /** 桩在 PLAN 阶段产出的那一条查询。 */
    public static final String STUB_QUERY = "stub-planning-query";

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "app.chat", name = "gateway", havingValue = "stub")
    public ChatModelGateway stubChatModelGateway() {
        return new StubChatModelGateway();
    }

    static class StubChatModelGateway implements ChatModelGateway {

        @Override
        public String chat(Long modelId, String systemPrompt, String userPrompt) {
            return STUB_ANSWER;
        }

        @Override
        public String chat(Long modelId, BigDecimal temperature, String systemPrompt,
                           String userPrompt) {
            return STUB_ANSWER;
        }

        @Override
        public <T> T chatStructured(Long modelId, String systemPrompt, String userPrompt,
                                    Class<T> type) {
            return structured(type);
        }

        @Override
        public <T> T chatStructured(Long modelId, BigDecimal temperature, String systemPrompt,
                                    String userPrompt, Class<T> type) {
            return structured(type);
        }

        private <T> T structured(Class<T> type) {
            Object value;
            if (type == PlanResult.class) {
                value = new PlanResult(List.of(STUB_QUERY));
            } else if (type == ReflectResult.class) {
                value = new ReflectResult(true, List.of(), List.of(), "stub draft");
            } else {
                throw new IllegalStateException("unsupported structured type: " + type.getName());
            }
            return type.cast(value);
        }
    }
}
