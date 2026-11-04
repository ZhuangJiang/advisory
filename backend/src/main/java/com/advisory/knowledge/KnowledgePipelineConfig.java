package com.advisory.knowledge;

import com.advisory.knowledge.ingest.DocumentParser;
import com.advisory.knowledge.ingest.EmbeddingClientFactory;
import com.advisory.knowledge.ingest.EmbeddingGateway;
import com.advisory.knowledge.ingest.StructureAwareChunker;
import com.advisory.knowledge.ingest.SpringAiEmbeddingGateway;
import com.advisory.knowledge.ingest.TikaDocumentParser;
import com.advisory.provider.service.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Beans of the real ingest pipeline.
 *
 * <p>The Spring AI gateway is only registered when {@code app.embedding.gateway} is unset (or
 * {@code real}); {@code IntegrationTestBase} sets it to {@code stub} so every integration test gets
 * the deterministic stub bean from {@code com.advisory.support.TestEmbeddingGatewayConfig} instead.
 * This property is deliberately absent from {@code application.yml}: in production the value must
 * never be set.</p>
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgePipelineConfig {

    @Bean
    public DocumentParser documentParser() {
        return new TikaDocumentParser();
    }

    @Bean
    public StructureAwareChunker structureAwareChunker() {
        return new StructureAwareChunker();
    }

    /** Programmatic Spring AI client construction site (no starter auto-configuration, ruling R1). */
    @Bean
    public EmbeddingClientFactory embeddingClientFactory(JdbcTemplate jdbc,
                                                        @Value("${app.embedding.dimension}") int dimension,
                                                        CryptoService cryptoService) {
        return new EmbeddingClientFactory(jdbc, dimension, cryptoService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.embedding", name = "gateway", havingValue = "real",
            matchIfMissing = true)
    public EmbeddingGateway embeddingGateway(EmbeddingClientFactory clientFactory,
                                             @Value("${app.embedding.dimension}") int dimension) {
        return new SpringAiEmbeddingGateway(clientFactory, dimension);
    }
}
