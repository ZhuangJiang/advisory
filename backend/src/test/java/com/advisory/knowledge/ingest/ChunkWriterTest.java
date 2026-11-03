package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The guards of {@link ChunkWriter#replaceAll} run before any DELETE: a chunk/vector count
 * mismatch is an internal contract violation ({@code INTERNAL_ERROR}), while a vector of the
 * wrong length stays a provider-facing {@code EMBEDDING_DIMENSION_MISMATCH}.
 */
class ChunkWriterTest {

    @Test
    void countMismatchIsAnInternalErrorNotADimensionMismatch() {
        ChunkWriter writer = new ChunkWriter(null, 1024);
        List<TextChunk> chunks = List.of(new TextChunk(0, "", "one"));
        List<float[]> vectors = List.of(new float[1024], new float[1024]);

        AppException exception = assertThrows(AppException.class,
                () -> writer.replaceAll(1L, 2L, null, chunks, vectors));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.getCode());
    }

    @Test
    void dimensionMismatchStaysOnTheDimensionErrorCode() {
        ChunkWriter writer = new ChunkWriter(null, 1024);
        List<TextChunk> chunks = List.of(new TextChunk(0, "", "one"));
        List<float[]> vectors = List.of(new float[3]);

        AppException exception = assertThrows(AppException.class,
                () -> writer.replaceAll(1L, 2L, null, chunks, vectors));

        assertEquals(ErrorCode.EMBEDDING_DIMENSION_MISMATCH, exception.getCode());
    }
}
