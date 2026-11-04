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
 * The gateway's own dimension guard, unit level: a fake {@link EmbeddingModel} (no Spring context,
 * no network, no database) returns vectors of a wrong or right dimension and the gateway must
 * behave as specified — mismatch = {@code EMBEDDING_DIMENSION_MISMATCH}, right dimension = pass
 * through. Also proves the 32-batch split.
 */
class SpringAiEmbeddingGatewayTest {

    @Test
    void wrongDimensionAbortsEveryVector() {
        SpringAiEmbeddingGateway gateway = new SpringAiEmbeddingGateway(
                fakeFactoryReturning((request) -> responseOf(request.getInstructions(), 3)), 1024);

        AppException exception = assertThrows(AppException.class,
                () -> gateway.embed(7L, List.of("a", "b", "c")));
        assertEquals(ErrorCode.EMBEDDING_DIMENSION_MISMATCH, exception.getCode());
        assertTrue(exception.getMessage().contains("dimension"),
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

    // ------------------------------------------------------------------ fakes

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

    /** A provider-less in-memory embedding model; the constructor options are irrelevant. */
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
