package com.advisory.knowledge.retrieval;

/**
 * 一条检索到的片段及其引用元数据。
 *
 * <p>这些字段正是最终答案里 {@code [n]} 角标所需的全部信息，因此从检索层一路带到持久化，
 * 不必在渲染时再回表查询。</p>
 *
 * @param chunkId    {@code chunks.id}
 * @param documentId {@code documents.id}
 * @param kbId       {@code knowledge_bases.id}
 * @param nodeId     {@code kb_nodes.id}，未挂节点的片段为 {@code null}
 * @param docName    {@code documents.filename}
 * @param nodeName   {@code kb_nodes.name}，未挂节点的片段为 {@code null}
 * @param content    {@code chunks.content}
 * @param score      来自 {@link RetrievalJdbcRepository} 的有序列表时是原始路径得分（余弦或三元组相似度）；
 *                   由 {@link RetrievalService#retrieve} 返回时则是 RRF 融合分 {@code Σ 1/(60+rank)}
 */
public record RetrievedChunk(Long chunkId, Long documentId, Long kbId, Long nodeId,
                             String docName, String nodeName, String content, double score) {
}
