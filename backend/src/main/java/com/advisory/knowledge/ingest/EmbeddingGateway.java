package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;

import java.util.List;

/**
 * Turns texts into vectors for one configured embedding model ({@code models.id}).
 *
 * <ul>
 *   <li>Batches of 32 texts per call; retries twice with 1s/2s backoff; 60s timeout per call.</li>
 *   <li>Every returned vector is validated against {@code app.embedding.dimension}; on a mismatch the
 *       whole call fails with {@link ErrorCode#EMBEDDING_DIMENSION_MISMATCH} and the caller must not
 *       have written anything (chunk storage happens only after this method returns).</li>
 *   <li>Never logs text contents — well, it never logs anything at INFO+: documents may be
 *       sensitive, so a failure log carries only exception metadata.</li>
 * </ul>
 */
public interface EmbeddingGateway {

    /**
     * @param embeddingModelId {@code models.id}, resolved to {@code model_providers.type/base_url} /
     *                         {@code models.model_name} by the implementation
     * @param texts            chunk texts, in document order
     * @return one vector per text, same order, each of length {@code app.embedding.dimension}
     * @throws AppException {@code EMBEDDING_DIMENSION_MISMATCH} (40010) for a wrong vector length,
     *                      or {@code VALIDATION_FAILED} when the model does not resolve to an
     *                      enabled {@code EMBEDDING} capability model
     */
    List<float[]> embed(Long embeddingModelId, List<String> texts);
}
