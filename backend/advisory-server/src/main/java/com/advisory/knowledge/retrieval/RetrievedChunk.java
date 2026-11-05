package com.advisory.knowledge.retrieval;

/**
 * 一条检索到的片段及其引用元数据。
 *
 * <p>这些字段正是最终答案里 {@code [n]} 角标所需的全部信息，因此从检索层一路带到持久化，
 * 不必在渲染时再回表查询。</p>
 *
 * @param chunkId    分块主键，对应 {@code chunks.id}
 * @param documentId 所属文档主键，对应 {@code documents.id}
 * @param kbId       所属知识库主键，对应 {@code knowledge_bases.id}
 * @param nodeId     所属节点主键，对应 {@code kb_nodes.id}；未挂节点的片段为 {@code null}
 * @param docName    文件名，取自 {@code documents.filename}，用于渲染来源标题
 * @param nodeName   节点名，取自 {@code kb_nodes.name}；未挂节点的片段为 {@code null}
 * @param content    分块正文，即最终拼进提示词的那段文本，取自 {@code chunks.content}
 * @param score      来自 {@link RetrievalJdbcRepository} 的有序列表时是原始路径得分（余弦或三元组相似度）；
 *                   由 {@link RetrievalService#retrieve} 返回时则是 RRF 融合分 {@code Σ 1/(60+rank)}
 */
public record RetrievedChunk(Long chunkId, Long documentId, Long kbId, Long nodeId,
                             String docName, String nodeName, String content, double score) {
}
