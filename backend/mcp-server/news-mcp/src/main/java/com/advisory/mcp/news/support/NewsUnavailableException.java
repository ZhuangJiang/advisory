package com.advisory.mcp.news.support;

/**
 * 所有新闻源都抓取失败时抛出。工具层把它翻译成固定文案返回，不把异常抛给模型。
 */
public class NewsUnavailableException extends RuntimeException {

    public NewsUnavailableException(String message) {
        super(message);
    }

    public NewsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
