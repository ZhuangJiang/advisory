package com.advisory.mcp.news.service;

import com.advisory.mcp.news.client.RssFeedClient;
import com.advisory.mcp.news.client.RssParser;
import com.advisory.mcp.news.config.NewsProperties;
import com.advisory.mcp.news.model.NewsItem;
import com.advisory.mcp.news.support.NewsUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RSS 聚合数据源：并行抓取全部配置源，按源缓存，单个源失败不影响其他源。
 *
 * <p>三个设计取舍：</p>
 * <ul>
 *   <li><b>并行</b>：6 个源串行 × 每个最多 5 秒，首次调用就可能撞上工具超时；并行把上界压到单个源的时间。</li>
 *   <li><b>按源缓存</b>：一个源挂了不该让其他源的缓存一起失效。TTL 默认 5 分钟。</li>
 *   <li><b>失败保旧</b>：抓取失败但手里有上一轮数据时，继续用旧数据（并把重试推后 1 分钟），
 *       比直接返回空更接近「可用」——新闻差几分钟不算错，整段回答消失才是。</li>
 * </ul>
 */
@Component
public class RssProvider implements NewsProvider {

    private static final Logger log = LoggerFactory.getLogger(RssProvider.class);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private record Cached(List<NewsItem> items, Instant expiresAt) {
    }

    private final RssFeedClient client;
    private final RssParser parser;
    private final NewsProperties properties;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ExecutorService fetchers = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "news-fetch");
        thread.setDaemon(true);
        return thread;
    });

    public RssProvider(RssFeedClient client, RssParser parser, NewsProperties properties) {
        this.client = client;
        this.parser = parser;
        this.properties = properties;
    }

    @Override
    public String sourceName() {
        int feeds = properties.feeds().size();
        return "RSS 聚合（" + feeds + " 个新闻源）";
    }

    @Override
    public List<NewsItem> items() {
        Instant now = Instant.now();
        List<NewsItem> collected = new ArrayList<>();
        List<CompletableFuture<List<NewsItem>>> pending = new ArrayList<>();

        for (NewsProperties.Feed feed : properties.feeds()) {
            Cached cached = cache.get(feed.url());
            if (cached != null && cached.expiresAt().isAfter(now)) {
                collected.addAll(cached.items());
                continue;
            }
            pending.add(CompletableFuture.supplyAsync(() -> load(feed, cached), fetchers));
        }

        // 等待上界取「单源超时 × 3」：正常情况下所有源同时在跑，这里只是兜底，
        // 防止某个实现忘设超时时把调用线程永久挂住。
        long waitMillis = Math.max(5_000L, properties.timeout().toMillis() * 3);
        for (CompletableFuture<List<NewsItem>> future : pending) {
            try {
                collected.addAll(future.get(waitMillis, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                log.warn("等待新闻源结果超时/失败：{}", rootMessage(e));
            }
        }

        if (collected.isEmpty()) {
            throw new NewsUnavailableException("所有新闻源都不可用");
        }
        return collected;
    }

    @PreDestroy
    public void shutdown() {
        fetchers.shutdownNow();
    }

    private List<NewsItem> load(NewsProperties.Feed feed, Cached stale) {
        try {
            List<NewsItem> items = parser.parse(feed, client.fetch(feed.url()));
            cache.put(feed.url(), new Cached(items, Instant.now().plus(properties.cacheTtl())));
            log.debug("新闻源 {} 刷新成功，{} 条", feed.name(), items.size());
            return items;
        } catch (RuntimeException e) {
            log.warn("新闻源 {} 抓取失败（{}）：{}", feed.name(), feed.url(), rootMessage(e));
            // 失败也写缓存：把下一次重试推后，避免每次对话都去锤一个挂掉的源
            List<NewsItem> fallback = stale == null ? List.of() : stale.items();
            cache.put(feed.url(), new Cached(fallback, Instant.now().plus(RETRY_AFTER_FAILURE)));
            return fallback;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
