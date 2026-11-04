package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Spring AI 的 {@link EmbeddingGateway} 实现，数据来自 {@code models} 与 {@code model_providers}。
 *
 * <p>每批 32 条文本，提供商调用外层重试两次（退避 1 秒、2 秒）；每个返回向量都在方法返回<em>之前</em>
 * 与 {@code app.embedding.dimension} 比对，因此维度不符的数据绝不会流到 {@link ChunkWriter}
 * （那里会在自己的事务内再校验一次）。失败日志只记异常元数据，绝不打印片段或文档正文。</p>
 *
 * <p>Spring AI 客户端的构造被 {@link EmbeddingClientFactory} 收在一处，本类因此可以用假工厂做单元测试、
 * 完全不联网。bean 由 {@code KnowledgePipelineConfig} 按 {@code app.embedding.gateway} 条件装配，
 * 而不是标成普通组件——否则它会被无条件注册。</p>
 */
public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    static final int BATCH_SIZE = 32;
    /** 首次调用加两次重试，与接口约定一致。 */
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MILLIS = {1000, 2000};

    private final EmbeddingClientFactory clientFactory;
    private final int dimension;

    public SpringAiEmbeddingGateway(EmbeddingClientFactory clientFactory,
                                    @Value("${app.embedding.dimension}") int dimension) {
        this.clientFactory = clientFactory;
        this.dimension = dimension;
    }

    /** 批量向量化并在返回前校验维度。 */
    @Override
    public List<float[]> embed(Long embeddingModelId, List<String> texts) {
        Objects.requireNonNull(embeddingModelId, "embeddingModelId");
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) {
            return List.of();
        }
        EmbeddingClientFactory.EmbeddingClient client = clientFactory.forEmbedding(embeddingModelId);

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(start + BATCH_SIZE, texts.size()));
            for (float[] vector : embedWithRetry(client, batch)) {
                validateDimension(vector);
                vectors.add(vector);
            }
        }
        return vectors;
    }

    /** 仅为单元测试放开可见性：该测试只断言这道校验本身。 */
    int dimension() {
        return dimension;
    }

    private void validateDimension(float[] vector) {
        if (vector == null || vector.length != dimension) {
            int actual = vector == null ? 0 : vector.length;
            throw new AppException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                    "Embedding dimension mismatch: expected " + dimension + ", got " + actual);
        }
    }

    /**
     * 调用提供商；任何异常都重试两次（退避 1 秒、2 秒），最终仍失败则抛出最后一次异常。
     *
     * <p>此处不校验维度——那是在 {@link #embed} 里、整批返回之后逐向量进行的，避免半批数据被判定为可用。</p>
     *
     * @param client 目标模型客户端
     * @param batch  本批文本
     * @return 与文本一一对应的向量
     */
    private List<float[]> embedWithRetry(EmbeddingClientFactory.EmbeddingClient client,
                                         List<String> batch) {
        EmbeddingModel model = client.model();
        EmbeddingOptions options = client.options();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                EmbeddingResponse response = model.call(new EmbeddingRequest(batch, options));
                List<float[]> out = new ArrayList<>(response.getResults().size());
                for (Embedding embedding : response.getResults()) {
                    out.add(embedding.getOutput());
                }
                return out;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(BACKOFF_MILLIS[attempt - 1]);
                }
            }
        }
        throw lastFailure == null
                ? new AppException(ErrorCode.INTERNAL_ERROR, "embedding provider call failed")
                : lastFailure;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.INTERNAL_ERROR, "embedding retry interrupted");
        }
    }
}
