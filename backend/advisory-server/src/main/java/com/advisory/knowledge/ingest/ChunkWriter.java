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
 * 幂等的片段存储：{@link #replaceAll} 在同一个事务里先删掉该文档的全部片段，再写入新的一批，
 * 因此一次失败的或重试的入库，库里要么全是旧数据、要么全是新数据，绝不会出现新旧混排。
 *
 * <p>所有校验都在 {@code DELETE} <em>之前</em>完成（调用方与 {@link SpringAiEmbeddingGateway} 也会校验，
 * 但这里才是约束可被证明的地方）：片段数与向量数不一致属于内部契约被破坏
 * （{@link ErrorCode#INTERNAL_ERROR}）；每个向量必须正好是 {@code app.embedding.dimension} 维，
 * 否则一行都不写并抛 {@link ErrorCode#EMBEDDING_DIMENSION_MISMATCH}。{@code seq} 直接取自
 * {@link TextChunk#seq()}，不做任何重排，保证与文档顺序一致。</p>
 */
@Component
public class ChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(ChunkWriter.class);

    private static final String DELETE_SQL = "DELETE FROM chunks WHERE document_id = ?";

    private static final String INSERT_SQL = """
            INSERT INTO chunks (document_id, kb_id, node_id, seq, heading_path, content, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector)
            """;

    private final JdbcTemplate jdbc;
    private final int dimension;

    public ChunkWriter(JdbcTemplate jdbc,
                       @Value("${app.embedding.dimension}") int dimension) {
        this.jdbc = jdbc;
        this.dimension = dimension;
    }

    /**
     * 用新一批片段整体替换该文档的旧片段。
     *
     * @param documentId 所属文档
     * @param kbId       知识库 id（列 {@code chunks.kb_id}），冗余存储以便检索时免去关联
     * @param nodeId     文档所在节点，可为 {@code null}
     * @param chunks     按文档顺序排列的片段
     * @param vectors    与片段一一对应的向量，每个长度均为 {@code app.embedding.dimension}
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
                    chunks.get(i).headingPath(),
                    chunks.get(i).content(),
                    toPgVectorText(vectors.get(i))});
        }
        jdbc.batchUpdate(INSERT_SQL, batch);
        log.debug("Stored {} chunk(s) of document {}", chunks.size(), documentId);
    }

    private void validateBeforeAnyDelete(long documentId, List<TextChunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            // 这是 worker 与 writer 之间的内部契约被破坏，不是提供商的问题：
            // 网关保证每个片段有且只有一个向量，数量对不上说明是我们自己的 bug。
            throw new AppException(ErrorCode.INTERNAL_ERROR,
                    "嵌入数量不匹配：文档 " + documentId + " 有 " + chunks.size() + " 个分块，但返回了 " + vectors.size() + " 个向量");
        }
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != dimension) {
                int actual = vector == null ? 0 : vector.length;
                throw new AppException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                        "嵌入维度不匹配：期望 " + dimension + "，实际 " + actual + "（文档 " + documentId + " 的第 " + chunks.get(i).seq() + " 个分块）");
            }
        }
    }

    /** pgvector 的文本格式 {@code [f1,f2,...]}；strtof 能解析 Java 的浮点写法（含指数形式）。 */
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
