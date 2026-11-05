package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.provider.service.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关自身的维度守卫，纯单元级：用一个假的 {@link EmbeddingModel}（无 Spring 上下文、不联网、
 * 不碰数据库）分别返回维度过长过短的向量与正确维度的向量，网关必须按约定行事——
 * 维度不符抛 {@code EMBEDDING_DIMENSION_MISMATCH}，维度正确则原样透传。同时验证按 32 条分批。
 */
class SpringAiEmbeddingGatewayTest {

    @Test
    void wrongDimensionAbortsEveryVector() {
        SpringAiEmbeddingGateway gateway = new SpringAiEmbeddingGateway(
                fakeFactoryReturning((request) -> responseOf(request.getInstructions(), 3)), 1024);

        AppException exception = assertThrows(AppException.class,
                () -> gateway.embed(7L, List.of("a", "b", "c")));
        assertEquals(ErrorCode.EMBEDDING_DIMENSION_MISMATCH, exception.getCode());
        assertTrue(exception.getMessage().contains("维度"),
                "message must mention the dimension: " + exception.getMessage());
    }

    @Test
    void correctDimensionsPassThrough() {
        SpringAiEmbeddingGateway gateway = new SpringAiEmbeddingGateway(
                fakeFactoryReturning((request) -> responseOf(request.getInstructions(), 1024)), 1024);

        List<float[]> vectors = gateway.embed(7L, List.of("a", "b", "c"));

        assertEquals(3, vectors.size());
        for (float[] vector : vectors) {
            assertEquals(1024, vector.length);
        }
    }

    @Test
    void splitsIntoBatchesOfAtMost32() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> batchSizes = new ArrayList<>();
        CallFunction typedFunction = request -> {
            calls.incrementAndGet();
            batchSizes.add(request.getInstructions().size());
            return responseOf(request.getInstructions(), 1024);
        };

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            texts.add("chunk-" + i);
        }
        new SpringAiEmbeddingGateway(fakeFactoryReturning(typedFunction), 1024)
                .embed(7L, texts);

        assertEquals(3, calls.get(), "70 texts must split into 3 calls");
        assertEquals(List.of(32, 32, 6), batchSizes);
    }

    // ------------------------------------------------------------------ 假实现

    @FunctionalInterface
    private interface CallFunction {
        EmbeddingResponse call(EmbeddingRequest request);
    }

    private static EmbeddingClientFactory fakeFactoryReturning(CallFunction function) {
        return new EmbeddingClientFactory(null, 1024,
                new CryptoService("unit-test-encryption-key")) {
            @Override
            public EmbeddingClient forEmbedding(long embeddingModelId) {
                return new EmbeddingClient(new FixedEmbeddingModel(1024, function), null);
            }
        };
    }

    private static EmbeddingResponse responseOf(List<String> texts, int dimensions) {
        List<Embedding> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(new float[dimensions], i));
        }
        return new EmbeddingResponse(embeddings);
    }

    /** 不依赖任何提供商的内存 embedding 模型；构造参数无关紧要。 */
    private record FixedEmbeddingModel(int dimensions, CallFunction function) implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return function.call(request);
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            throw new UnsupportedOperationException();
        }
    }
}
