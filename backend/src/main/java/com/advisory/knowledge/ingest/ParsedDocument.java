package com.advisory.knowledge.ingest;

import java.util.List;

/**
 * 文档解析的结果。
 *
 * @param markdown 归一化后的、接近 Markdown 的文本，标题统一保留为 ATX 形式
 * @param sections 按文档顺序排列的标题章节
 */
public record ParsedDocument(String markdown, List<ParsedSection> sections) {

    public ParsedDocument {
        markdown = markdown == null ? "" : markdown;
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
