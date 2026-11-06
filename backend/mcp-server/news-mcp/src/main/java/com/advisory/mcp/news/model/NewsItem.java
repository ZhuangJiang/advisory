package com.advisory.mcp.news.model;

import java.time.Instant;

/**
 * 一条新闻。字段刻意少：工具输出最终是给人读的文本，模型只需要「标题 + 来源 + 时间 + 链接」，
 * 多余字段只会让上下文变长。
 *
 * @param title       标题（已去掉 HTML 标签）
 * @param link        原文链接
 * @param source      来源名，取配置里的 feed.name
 * @param category    分类（科技 / 财经 / 综合），用于头条筛选
 * @param publishedAt 发布时间；源没给时间时为 null，排序时排最后而不是假装是现在
 * @param summary     摘要（纯文本，已截断）；用于展示，可能为空串
 * @param content     正文全文（已清洗、限长），**只用于关键词匹配**，不进模型上下文——
 *                    RSS 的标题常常不含关键词，正文里却有；只按标题匹配会漏掉大量真实结果
 */
public record NewsItem(
        String title,
        String link,
        String source,
        String category,
        Instant publishedAt,
        String summary,
        String content) {

    public boolean hasTime() {
        return publishedAt != null;
    }

    /** 匹配用文本：标题 + 摘要 + 正文。 */
    public String searchableText() {
        return (title == null ? "" : title) + " "
                + (summary == null ? "" : summary) + " "
                + (content == null ? "" : content);
    }
}
