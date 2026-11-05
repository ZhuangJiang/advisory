package com.advisory.knowledge.ingest;

/**
 * 可以直接送去向量化的片段。
 *
 * @param seq         在文档中的位置，从 0 开始
 * @param headingPath 所属章节的 {@code "A > B > C"} 路径；文档没有任何标题时为空串
 * @param content     片段文本（可能与上一片段存在重叠）
 */
public record TextChunk(int seq, String headingPath, String content) {

    public TextChunk {
        headingPath = headingPath == null ? "" : headingPath;
        content = content == null ? "" : content;
    }
}
