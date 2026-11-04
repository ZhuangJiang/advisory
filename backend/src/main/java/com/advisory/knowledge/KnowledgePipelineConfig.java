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
 * 真实入库流水线所需的 bean。
 *
 * <p>只有 {@code app.embedding.gateway} 未设置（或为 {@code real}）时才注册 Spring AI 网关；
 * {@code IntegrationTestBase} 把它设为 {@code stub}，集成测试因此统一使用
 * {@code com.advisory.support.TestEmbeddingGatewayConfig} 里那个确定性的桩 bean。该配置项刻意不写进
 * {@code application.yml}：生产环境永远不该设置它。</p>
 */
@Configuration(proxyBeanMethods = false)
public class KnowledgePipelineConfig {

    /** 基于 Tika 的文档解析器。 */
    @Bean
    public DocumentParser documentParser() {
        return new TikaDocumentParser();
    }

    /** 结构化切片器。 */
    @Bean
    public StructureAwareChunker structureAwareChunker() {
        return new StructureAwareChunker();
    }

    /** Spring AI 客户端的手工构造点（不使用 starter 自动配置，裁决 R1）。 */
    @Bean
    public EmbeddingClientFactory embeddingClientFactory(JdbcTemplate jdbc,
                                                        @Value("${app.embedding.dimension}") int dimension,
                                                        CryptoService cryptoService) {
        return new EmbeddingClientFactory(jdbc, dimension, cryptoService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.embedding", name = "gateway", havingValue = "real",
            matchIfMissing = true)
    /** 真实向量网关：gateway 为 stub 时让位给测试桩。 */
    public EmbeddingGateway embeddingGateway(EmbeddingClientFactory clientFactory,
                                             @Value("${app.embedding.dimension}") int dimension) {
        return new SpringAiEmbeddingGateway(clientFactory, dimension);
    }
}
