package com.advisory.mcp.news.tool;

import com.advisory.mcp.news.model.NewsItem;
import com.advisory.mcp.news.service.NewsRanking;
import com.advisory.mcp.news.service.NewsService;
import com.advisory.mcp.news.support.NewsUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * MCP 工具本体。
 *
 * <p>沿用 weather-mcp 的两条约定：工具永不抛异常（异常在模型那里只是一段报错文本，
 * 还会打断它组织回答）；返回值是给模型读的纯文本，不是 JSON。</p>
 *
 * <p>时间统一按 Asia/Shanghai 渲染：容器默认时区是 UTC，直接格式化会让「2 小时前」的新闻
 * 显示成 8 小时前，用户一眼就发现不对。</p>
 */
@Component
public class NewsTools {

    private static final Logger log = LoggerFactory.getLogger(NewsTools.class);
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 5;
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(DISPLAY_ZONE);

    private final NewsService news;

    public NewsTools(NewsService news) {
        this.news = news;
    }

    @Tool(name = "search_news",
            description = "按关键词搜索最近新闻，返回标题、来源、发布时间、摘要与原文链接。"
                    + "适用于「最近有什么关于 XX 的新闻」「XX 的最新动态」「XX 出台了什么政策」这类"
                    + "需要时效性信息的问题。多个关键词用空格分隔。")
    public String searchNews(
            @ToolParam(description = "搜索关键词，中英文均可，例如：新能源汽车、碳交易、人工智能；多个词用空格分隔")
            String keyword,
            @ToolParam(description = "返回条数，1 到 10 之间的整数；用户没说时用 5") int limit) {

        if (keyword == null || keyword.isBlank()) {
            return "请提供搜索关键词，例如「碳交易」或「新能源汽车」。";
        }
        String normalizedLimitError = validateLimit(limit);
        if (normalizedLimitError != null) {
            return normalizedLimitError;
        }

        try {
            List<NewsItem> items = news.search(keyword.trim(), limit);
            if (items.isEmpty()) {
                return String.format("没有找到与「%s」相关的新闻。可以换个更宽泛的关键词"
                        + "（例如把「欧盟碳边境调节机制」换成「碳关税」），或直接问「今天有什么新闻」。",
                        keyword.trim());
            }
            return render(items, String.format("找到 %d 条与「%s」相关的新闻：", items.size(), keyword.trim()));
        } catch (NewsUnavailableException e) {
            log.warn("新闻搜索失败, keyword={}", keyword, e);
            return "新闻源暂时不可用，请稍后再试。";
        } catch (RuntimeException e) {
            log.error("新闻搜索异常, keyword={}", keyword, e);
            return "查询新闻时发生异常，请稍后再试。";
        }
    }

    @Tool(name = "get_headlines",
            description = "获取指定分类的最近新闻头条，返回标题、来源、发布时间、摘要与原文链接。"
                    + "适用于「今天有什么新闻」「最近科技圈有什么大事」这类没有明确关键词的时效性问题。")
    public String getHeadlines(
            @ToolParam(description = "新闻分类：科技、财经、综合；用户没说时用综合")
            String category,
            @ToolParam(description = "返回条数，1 到 10 之间的整数；用户没说时用 5") int limit) {

        String normalizedLimitError = validateLimit(limit);
        if (normalizedLimitError != null) {
            return normalizedLimitError;
        }
        if (!NewsRanking.isSupportedCategory(category)) {
            return String.format("不支持的分类「%s」。可用的分类：科技、财经、综合。", category);
        }
        String canonical = NewsRanking.canonicalCategory(category);

        try {
            List<NewsItem> items = news.headlines(canonical, limit);
            if (items.isEmpty()) {
                return String.format("「%s」分类暂时没有新闻，请稍后再试或换个分类。", canonical);
            }
            return render(items, String.format("%s头条（最近 %d 条）：", canonical, items.size()));
        } catch (NewsUnavailableException e) {
            log.warn("头条获取失败, category={}", canonical, e);
            return "新闻源暂时不可用，请稍后再试。";
        } catch (RuntimeException e) {
            log.error("头条获取异常, category={}", canonical, e);
            return "查询新闻时发生异常，请稍后再试。";
        }
    }

    private static String validateLimit(int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            return String.format("返回条数必须在 %d 到 %d 之间，收到的是 %d，请用正确的条数重试"
                    + "（用户没说时用 %d）。", MIN_LIMIT, MAX_LIMIT, limit, DEFAULT_LIMIT);
        }
        return null;
    }

    private String render(List<NewsItem> items, String header) {
        StringBuilder sb = new StringBuilder(header);
        for (int i = 0; i < items.size(); i++) {
            NewsItem item = items.get(i);
            sb.append(String.format("%n%d. %s", i + 1, item.title()));
            sb.append(String.format("%n   来源：%s · %s", item.source(), time(item)));
            if (item.summary() != null && !item.summary().isBlank()) {
                sb.append(String.format("%n   摘要：%s", item.summary()));
            }
            if (item.link() != null && !item.link().isBlank()) {
                sb.append(String.format("%n   链接：%s", item.link()));
            }
        }
        sb.append(String.format("%n数据来源：%s", news.sourceName()));
        return sb.toString();
    }

    private static String time(NewsItem item) {
        return item.hasTime() ? TIME_FORMAT.format(item.publishedAt()) : "发布时间未知";
    }
}
