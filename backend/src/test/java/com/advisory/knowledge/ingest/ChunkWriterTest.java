package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChunkWriter#replaceAll} 的各项守卫都在任何 DELETE 之前执行：分块数与向量数不一致属于
 * 内部契约被破坏（{@code INTERNAL_ERROR}），而向量长度不对则仍归为面向提供商的
 * {@code EMBEDDING_DIMENSION_MISMATCH}。
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
