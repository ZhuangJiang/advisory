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

    /**
     * 用已保存的 Markdown 重建解析结果，章节按同一份文本重新推导。
     *
     * <p>给「重新裁切」用：解析产物已经落在对象存储里，没必要再把原始文件交给 Tika 跑一遍。
     * 因为章节完全由 Markdown 决定，这条路径切出来的片段与完整解析路径一致。</p>
     *
     * @param markdown 解析时保存的 Markdown
     * @return 可直接交给切片器的解析结果
     */
    public static ParsedDocument ofMarkdown(String markdown) {
        String text = markdown == null ? "" : markdown;
        return new ParsedDocument(text, MarkdownStructure.sections(text));
    }
}
