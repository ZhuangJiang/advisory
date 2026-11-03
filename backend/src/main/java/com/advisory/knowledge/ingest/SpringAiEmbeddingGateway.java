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
 * Spring AI backed {@link EmbeddingGateway} over the {@code models}/{@code model_providers} rows.
 *
 * <p>Batches of 32 texts; two retries (1s, 2s) around the provider call; every returned vector is
 * checked against {@code app.embedding.dimension} <em>before</em> the method returns, so a
 * dimension mismatch can never reach {@link ChunkWriter} (which re-checks inside its own
 * transaction). Failures never log chunk or document text — only exception metadata.</p>
 *
 * <p>{@link EmbeddingClientFactory} keeps Spring AI client construction in one place, which makes
 * this class unit-testable with a fake factory and no network. The bean itself lives in
 * {@code KnowledgePipelineConfig} (conditioned on {@code app.embedding.gateway}); it is not a plain
 * component so it is never registered unconditionally.</p>
 */
public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    static final int BATCH_SIZE = 32;
    /** Initial call plus two retries, per the interface contract. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MILLIS = {1000, 2000};

    private final EmbeddingClientFactory clientFactory;
    private final int dimension;

    public SpringAiEmbeddingGateway(EmbeddingClientFactory clientFactory,
                                    @Value("${app.embedding.dimension}") int dimension) {
        this.clientFactory = clientFactory;
        this.dimension = dimension;
    }

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

    /** Visible for the unit test that asserts the guard itself and nothing else. */
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
     * Calls the provider; on any exception retries twice with 1s/2s backoff and rethrows the last
     * failure. Nothing is validated against the dimension here — that happens to each vector in
     * {@link #embed}, after the batch came back whole.
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
