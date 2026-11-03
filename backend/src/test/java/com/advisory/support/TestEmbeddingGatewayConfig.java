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
 * deterministic non-zero unit vectors derived from each text, so no test ever calls out to a real
 * provider. Tests that need other behavior replace it with {@code @MockitoBean EmbeddingGateway}.
 *
 * <p>Vectors are seeded by {@code text.hashCode()} — same text, same vector across calls — and
 * normalized to unit length so cosine distance against them is never NaN (a zero vector would
 * be).</p>
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

    /**
     * Deterministic non-zero unit vectors of the configured length: each text seeds a
     * {@link java.util.Random} via {@code text.hashCode()}, so the same text always embeds to the
     * same vector (and the vector is non-degenerate — a zero vector would make pgvector's cosine
     * distance NaN on the retrieval path). The model id is ignored.
     */
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
            for (String text : texts) {
                vectors.add(vectorFor(text));
            }
            return vectors;
        }

        private float[] vectorFor(String text) {
            java.util.Random random = new java.util.Random(text == null ? 0L : text.hashCode());
            float[] vector = new float[dimension];
            double normSq = 0d;
            for (int i = 0; i < dimension; i++) {
                float value = random.nextFloat() * 2f - 1f;
                vector[i] = value;
                normSq += (double) value * value;
            }
            if (normSq == 0d) {
                // Guard the (practically impossible) all-zero draw: cosine distance on a zero
                // vector is NaN, so the vector must carry at least one non-zero component.
                vector[0] = 1f;
                return vector;
            }
            float norm = (float) Math.sqrt(normSq);
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
            return vector;
        }
    }
}
