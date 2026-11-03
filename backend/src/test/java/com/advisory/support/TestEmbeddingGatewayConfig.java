package com.advisory.support;

import com.advisory.knowledge.ingest.EmbeddingGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic {@link EmbeddingGateway} stub for every {@code @SpringBootTest} context: it returns
 * zero vectors of {@code app.embedding.dimension} length, so no test ever calls out to a real
 * provider. Tests that need other behavior replace it with {@code @MockitoBean EmbeddingGateway}.
 *
 * <p>A plain {@code @Configuration} class in the scanned {@code com.advisory} tree is intentional:
 * the main application's component scan registers it for every test, which is exactly what a shared
 * test fixture needs. (A top-level {@code @TestConfiguration} would be excluded from component
 * scanning, and a nested one would only apply to a single test class.) The real Spring AI gateway
 * (see {@code KnowledgePipelineConfig}) only defines its bean when {@code app.embedding.gateway=real};
 * {@link IntegrationTestBase} sets the property to {@code stub}, so this stub is the {@code
 * EmbeddingGateway} bean in test contexts — no ambiguity, no {@code @Primary} tricks.</p>
 */
@Configuration(proxyBeanMethods = false)
public class TestEmbeddingGatewayConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "app.embedding", name = "gateway", havingValue = "stub")
    public EmbeddingGateway stubEmbeddingGateway(@Value("${app.embedding.dimension}") int dimension) {
        return new StubEmbeddingGateway(dimension);
    }

    /** Zero vectors of the configured length; the model id and texts are ignored. */
    static class StubEmbeddingGateway implements EmbeddingGateway {

        private final int dimension;

        StubEmbeddingGateway(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public List<float[]> embed(Long embeddingModelId, List<String> texts) {
            int count = texts == null ? 0 : texts.size();
            List<float[]> vectors = new ArrayList<>(count);
            if (texts == null) {
                return vectors;
            }
            for (String ignored : texts) {
                vectors.add(new float[dimension]);
            }
            return vectors;
        }
    }
}
