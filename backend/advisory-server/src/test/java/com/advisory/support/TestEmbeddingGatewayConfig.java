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
 * 供每个 {@code @SpringBootTest} 上下文使用的确定性 {@link EmbeddingGateway} 桩：
 * 由文本推导出确定性的非零单位向量，任何用例都不会真的去调外部提供商。
 * 需要其它行为的用例可用 {@code @MockitoBean EmbeddingGateway} 替换它。
 *
 * <p>向量以 {@code text.hashCode()} 为随机种子——同样的文本在任何一次调用中都得到同样的向量——
 * 并归一化为单位长度，保证按其计算的余弦距离永远不是 NaN（零向量就会是）。</p>
 *
 * <p>刻意把它写成被扫描的 {@code com.advisory} 包下一个普通的 {@code @Configuration} 类：
 * 主应用的组件扫描会为每个测试注册它，这正好是共享测试夹具想要的效果。
 * （顶层 {@code @TestConfiguration} 会被组件扫描排除，嵌套的又只能作用于单个测试类。）
 * 真实的 Spring AI 网关（见 {@code KnowledgePipelineConfig}）只在
 * {@code app.embedding.gateway=real} 时才定义自己的 bean；而 {@link IntegrationTestBase}
 * 把该属性设成 {@code stub}，于是测试上下文里的 {@code EmbeddingGateway} bean 就是本桩——
 * 既不存在歧义，也不需要靠 {@code @Primary} 兜底。</p>
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
     * 按配置维度生成确定性的非零单位向量：每段文本用 {@code text.hashCode()} 给
     * {@link java.util.Random} 播种，于是相同文本永远得到相同向量，且向量不会退化——
     * 零向量会让检索链路上 pgvector 的余弦距离变成 NaN。模型 id 参数忽略不用。
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
                // 兜住「全零」这种几乎不可能出现的随机结果：零向量的余弦距离是 NaN，
                // 因此向量至少要有一个非零分量。
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
