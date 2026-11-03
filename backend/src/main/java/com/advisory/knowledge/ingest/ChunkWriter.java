package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Idempotent chunk storage: {@link #replaceAll} deletes a document's chunks and inserts the fresh
 * batch inside one transaction, so a {@code FAILED} or retried ingest leaves either the entire old
 * set or the entire new set, never a mixture.
 *
 * <p>Guards run <em>before</em> the {@code DELETE} (the caller and {@link SpringAiEmbeddingGateway}
 * validate too, but this is where the invariant is provable): chunk count must equal vector count
 * and every vector must carry exactly {@code app.embedding.dimension} dimensions, or nothing at all
 * is written and {@link ErrorCode#EMBEDDING_DIMENSION_MISMATCH} is thrown. {@code seq} comes from
 * {@link TextChunk#seq()} and is never renumbered.</p>
 */
@Component
public class ChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(ChunkWriter.class);

    private static final String DELETE_SQL = "DELETE FROM chunks WHERE document_id = ?";

    private static final String INSERT_SQL = """
            INSERT INTO chunks (document_id, kb_id, node_id, seq, content, embedding)
            VALUES (?, ?, ?, ?, ?, ?::vector)
            """;

    private final JdbcTemplate jdbc;
    private final int dimension;

    public ChunkWriter(JdbcTemplate jdbc,
                       @Value("${app.embedding.dimension}") int dimension) {
        this.jdbc = jdbc;
        this.dimension = dimension;
    }

    /**
     * @param documentId owning document
     * @param kbId       knowledge base id (column {@code chunks.kb_id})
     * @param nodeId     document node, may be {@code null}
     * @param chunks     chunks in document order
     * @param vectors    one vector of length {@code app.embedding.dimension} per chunk, same order
     */
    @Transactional
    public void replaceAll(Long documentId, Long kbId, Long nodeId,
                           List<TextChunk> chunks, List<float[]> vectors) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(kbId, "kbId");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(vectors, "vectors");
        validateBeforeAnyDelete(documentId, chunks, vectors);

        jdbc.update(DELETE_SQL, documentId);
        if (chunks.isEmpty()) {
            return;
        }
        List<Object[]> batch = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            batch.add(new Object[] {
                    documentId,
                    kbId,
                    nodeId,
                    chunks.get(i).seq(),
                    chunks.get(i).content(),
                    toPgVectorText(vectors.get(i))});
        }
        jdbc.batchUpdate(INSERT_SQL, batch);
        log.debug("Stored {} chunk(s) of document {}", chunks.size(), documentId);
    }

    private void validateBeforeAnyDelete(long documentId, List<TextChunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new AppException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                    "Embedding count mismatch: " + chunks.size() + " chunk(s) but "
                            + vectors.size() + " vector(s) for document " + documentId);
        }
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != dimension) {
                int actual = vector == null ? 0 : vector.length;
                throw new AppException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                        "Embedding dimension mismatch: expected " + dimension + ", got " + actual
                                + " (chunk seq " + chunks.get(i).seq() + " of document " + documentId + ")");
            }
        }
    }

    /** pgvector text form {@code [f1,f2,...]}; strtof parses Java float notation (incl. exponents). */
    static String toPgVectorText(float[] vector) {
        StringBuilder out = new StringBuilder(vector.length * 8 + 2);
        out.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector[i]);
        }
        out.append(']');
        return out.toString();
    }
}
