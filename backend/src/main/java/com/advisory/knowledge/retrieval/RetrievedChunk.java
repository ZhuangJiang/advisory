package com.advisory.knowledge.retrieval;

/**
 * One retrieved chunk with its citation metadata.
 *
 * @param chunkId    {@code chunks.id}
 * @param documentId {@code documents.id}
 * @param kbId       {@code knowledge_bases.id}
 * @param nodeId     {@code kb_nodes.id}, {@code null} for node-less chunks
 * @param docName    {@code documents.filename}
 * @param nodeName   {@code kb_nodes.name}, {@code null} for node-less chunks
 * @param content    {@code chunks.content}
 * @param score      path score before fusion (cosine similarity or trigram similarity), or the
 *                   fused RRF score after {@link RrfFusion#fuse}
 */
public record RetrievedChunk(Long chunkId, Long documentId, Long kbId, Long nodeId,
                             String docName, String nodeName, String content, double score) {
}
