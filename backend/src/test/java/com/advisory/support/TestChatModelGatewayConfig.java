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
 * Deterministic {@link ChatModelGateway} for every {@code @SpringBootTest} context.
 *
 * <p>Mirrors {@link TestEmbeddingGatewayConfig}: {@link IntegrationTestBase} sets
 * {@code app.chat.gateway=stub}, which keeps the real Spring AI gateway (see {@code ChatConfig})
 * unregistered, so no test can accidentally call a provider. The stub always plans one query,
 * always reports "sufficient" on the first round and always answers — which makes the orchestration
 * deterministic: one PLAN, one RETRIEVE, one REFLECT, one ANSWER per question.</p>
 *
 * <p>Tests that need a different model behaviour replace it with {@code @MockitoBean ChatModelGateway}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class TestChatModelGatewayConfig {

    /** What the stub answers; tests assert on it to prove the stream carried the real answer. */
    public static final String STUB_ANSWER = "根据知识库片段，答案是 [1]。";

    /** The single query the stub PLAN produces. */
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
