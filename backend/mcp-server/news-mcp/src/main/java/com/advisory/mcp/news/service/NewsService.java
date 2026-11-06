package com.advisory.mcp.news.service;

import com.advisory.mcp.news.config.NewsProperties;
import com.advisory.mcp.news.model.NewsItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 按配置选择数据源，并转发筛选请求。
 *
 * <p>与 weather-mcp 的 WeatherService 同样的理由：配置写错（比如 NEWS_PROVIDER=rrs）
 * 时在启动阶段抛一条看得懂的错误，而不是等到工具被调用才抛 NoSuchBeanDefinition。</p>
 */
@Component
public class NewsService {

    private final NewsProvider delegate;

    public NewsService(RssProvider rss, StubProvider stub, NewsProperties properties) {
        String configured = properties.provider() == null || properties.provider().isBlank()
                ? "rss"
                : properties.provider().trim().toLowerCase(Locale.ROOT);

        this.delegate = switch (configured) {
            case "rss" -> rss;
            case "stub" -> stub;
            default -> throw new IllegalStateException(
                    "app.news.provider 只支持 rss | stub，当前为：" + properties.provider());
        };
    }

    public List<NewsItem> search(String keyword, int limit) {
        return NewsRanking.search(delegate.items(), keyword, limit);
    }

    public List<NewsItem> headlines(String category, int limit) {
        return NewsRanking.headlines(delegate.items(), category, limit);
    }

    public String sourceName() {
        return delegate.sourceName();
    }
}
