package com.advisory.knowledge.ingest;

/**
 * 一个标题及其下属的原始 Markdown 正文。
 *
 * @param level       标题层级（1-6）
 * @param heading     标题文本，不含前面的井号
 * @param headingPath 从文档根节点开始的完整路径，例如 {@code "A > B > C"}
 * @param content     本标题与下一个标题之间的原始 Markdown
 */
public record ParsedSection(int level, String heading, String headingPath, String content) {

    public ParsedSection {
        heading = heading == null ? "" : heading;
        headingPath = headingPath == null ? "" : headingPath;
        content = content == null ? "" : content;
    }
}
