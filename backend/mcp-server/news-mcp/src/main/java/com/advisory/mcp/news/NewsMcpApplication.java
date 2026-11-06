package com.advisory.mcp.news;

import com.advisory.mcp.news.config.NewsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 新闻检索 MCP 服务（独立进程，端口 21002）。
 *
 * <p>与 weather-mcp 同构：SSE 传输、无数据库、数据源可切换（rss = 真实抓取，stub = 离线固定数据）。
 * 区别只在于它对外部世界是「读多写少、可缓存」——新闻的时效以分钟计，5 分钟缓存既够新，
 * 又能把 6 个 RSS 源的请求量压到很低（这些源没有配额，但也没有义务容忍高频抓取）。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(NewsProperties.class)
public class NewsMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewsMcpApplication.class, args);
    }
}
