package com.advisory.mcp.news.client;

import com.advisory.mcp.news.config.NewsProperties;
import com.advisory.mcp.news.support.NewsUnavailableException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 抓取 RSS 源原始字节。
 *
 * <p>为什么自己用 HttpClient 取字节、而不是让 Rome 直接读 URL：Rome 自己读流时既没法设超时，
 * 也不跟随重定向（少数派的 /feed 会 301），一个慢源就能把整次工具调用拖住。这里显式控制三件事：
 * 连接超时、请求超时、跟随重定向。</p>
 *
 * <p>带一个常见的浏览器 UA：部分站点对默认 Java UA 直接返回反爬页（HTML），
 * 解析阶段会失败，但失败原因看着像「格式不对」，排查起来很绕。</p>
 */
@Component
public class RssFeedClient {

    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; advisory-news-mcp/1.0; +https://localhost)";

    private final HttpClient http;
    private final Duration timeout;

    public RssFeedClient(NewsProperties properties) {
        this.timeout = properties.timeout();
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public byte[] fetch(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new NewsUnavailableException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new NewsUnavailableException("抓取失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NewsUnavailableException("抓取被中断", e);
        }
    }
}
