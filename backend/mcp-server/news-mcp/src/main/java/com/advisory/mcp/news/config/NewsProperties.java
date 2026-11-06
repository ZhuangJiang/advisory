package com.advisory.mcp.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 新闻服务配置。
 *
 * @param provider 数据源：rss（抓取配置的 RSS/Atom 源）| stub（固定假数据，供离线开发与测试）
 * @param timeout  单个源的抓取超时。多个源并行抓取，单个源慢不该拖住整次工具调用
 * @param cacheTtl 抓取结果的缓存时长。新闻以分钟计更新，5 分钟既够新又能把抓取频率压到很低
 * @param feeds    新闻源清单。加源/删源只改配置，不动代码；category 决定它出现在哪个头条分类里
 */
@ConfigurationProperties("app.news")
public record NewsProperties(
        @DefaultValue("rss") String provider,
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("5m") Duration cacheTtl,
        @DefaultValue List<Feed> feeds) {

    /**
     * 一个新闻源。
     *
     * @param name     展示给用户的来源名（出现在每条新闻的「来源」字段）
     * @param url      RSS/Atom 地址；必须返回 XML，返回 HTML（反爬页）的源会在解析阶段被跳过
     * @param category 头条分类：科技 / 财经 / 综合
     */
    public record Feed(String name, String url, String category) {
    }
}
