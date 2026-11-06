package com.advisory.mcp.news.service;

import com.advisory.mcp.news.model.NewsItem;

import java.util.List;

/**
 * 新闻数据来源。目前两个实现：RSS 聚合（真实抓取）与 Stub（离线固定数据）。
 * 工具层只依赖这个接口；将来网络条件允许时加 GDELT 等新源，不需要动工具与文案。
 */
public interface NewsProvider {

    /** 展示给用户的来源名，让「这是假数据」这件事在输出里可见。 */
    String sourceName();

    /**
     * 当前可用的全部新闻。RSS 实现内部按源缓存，这里不是每次调用都真的发网络请求。
     * 全部源都不可用时抛 {@code NewsUnavailableException}，由工具层翻译成可读文案。
     */
    List<NewsItem> items();
}
