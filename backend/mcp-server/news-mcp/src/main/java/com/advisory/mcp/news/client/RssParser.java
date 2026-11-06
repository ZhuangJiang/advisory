package com.advisory.mcp.news.client;

import com.advisory.mcp.news.config.NewsProperties;
import com.advisory.mcp.news.model.NewsItem;
import com.advisory.mcp.news.support.NewsUnavailableException;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * RSS/Atom 解析：字节 → {@link NewsItem}。
 *
 * <p>用 SAX 的 {@link InputSource} 而不是自己 new Reader：国内源里既有 UTF-8 也有 GBK，
 * 编码声明必须由解析器按 XML 头判断，硬编 UTF-8 会把 GBK 源整篇读成乱码。</p>
 *
 * <p>脏数据是常态：标题带 HTML、时间缺失、链接为空。这里统一清洗，保证下游拿到的
 * 每条记录都有可用标题——没有标题的条目直接丢弃，它对模型没有价值。</p>
 */
@Component
public class RssParser {

    private static final int MAX_SUMMARY_CHARS = 120;
    private static final int MAX_CONTENT_CHARS = 2_000;

    public List<NewsItem> parse(NewsProperties.Feed feed, byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new NewsUnavailableException("响应为空：" + feed.url());
        }
        SyndFeed parsed;
        try {
            parsed = new SyndFeedInput().build(new InputSource(new ByteArrayInputStream(payload)));
        } catch (Exception e) {
            // 反爬页（HTML）也走这里，错误信息里带上源的地址才定位得到
            throw new NewsUnavailableException("解析失败：" + feed.url() + "（" + e.getMessage() + "）", e);
        }

        List<NewsItem> items = new ArrayList<>();
        for (SyndEntry entry : parsed.getEntries()) {
            String title = clean(entry.getTitle());
            if (title.isEmpty()) {
                continue;
            }
            items.add(new NewsItem(
                    title,
                    entry.getLink() == null ? "" : entry.getLink().trim(),
                    feed.name(),
                    feed.category(),
                    publishedAt(entry),
                    summary(entry),
                    content(entry)));
        }
        return items;
    }

    private static java.time.Instant publishedAt(SyndEntry entry) {
        Date published = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        return published == null ? null : published.toInstant();
    }

    private static String summary(SyndEntry entry) {
        if (entry.getDescription() == null) {
            return "";
        }
        String text = clean(entry.getDescription().getValue());
        return text.length() <= MAX_SUMMARY_CHARS ? text : text.substring(0, MAX_SUMMARY_CHARS) + "…";
    }

    /**
     * 正文全文：description 往往只是开头，真正的正文在 content:encoded 里。
     * 只用于匹配、不进模型上下文，所以可以放宽到 2000 字（再长对相关性也几乎没有帮助）。
     */
    private static String content(SyndEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.getDescription() != null) {
            sb.append(entry.getDescription().getValue()).append(' ');
        }
        entry.getContents().forEach(content -> sb.append(content.getValue()).append(' '));
        String text = clean(sb.toString());
        return text.length() <= MAX_CONTENT_CHARS ? text : text.substring(0, MAX_CONTENT_CHARS);
    }

    /** 去标签、解常见实体、压缩空白。顺序不能反：先解实体再压缩，否则 &amp;nbsp; 之类的残留会留下怪字符。 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("\\s+", " ").trim();
    }
}
