package com.advisory.mcp.news.tool;

import com.advisory.mcp.news.model.NewsItem;
import com.advisory.mcp.news.service.NewsService;
import com.advisory.mcp.news.support.NewsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具层契约：永远返回可读文本；参数不合法时不打扰上游。
 * 时间断言按 Asia/Shanghai 渲染（容器默认 UTC，这是最容易回归的一处）。
 */
@ExtendWith(MockitoExtension.class)
class NewsToolsTest {

    @Mock
    private NewsService news;

    private NewsTools tools;

    @BeforeEach
    void setUp() {
        tools = new NewsTools(news);
    }

    @Test
    void formatsSearchResultsWithSourceTimeAndLink() {
        when(news.search("新能源", 2)).thenReturn(List.of(
                new NewsItem("新能源电池回收技术取得突破", "https://example.com/a", "IT之家", "科技",
                        Instant.parse("2026-11-06T04:00:00Z"), "多家企业启动中试线", ""),
                new NewsItem("没有发布时间的新闻", "https://example.com/b", "新浪要闻", "综合",
                        null, "", "")));
        when(news.sourceName()).thenReturn("RSS 聚合（6 个新闻源）");

        String result = tools.searchNews("新能源", 2);

        assertThat(result)
                .contains("找到 2 条与「新能源」相关的新闻")
                .contains("1. 新能源电池回收技术取得突破")
                .contains("来源：IT之家 · 2026-11-06 12:00")
                .contains("摘要：多家企业启动中试线")
                .contains("链接：https://example.com/a")
                .contains("发布时间未知")
                .contains("数据来源：RSS 聚合（6 个新闻源）");
    }

    @Test
    void trimsKeywordBeforeSearching() {
        when(news.search("碳交易", 5)).thenReturn(List.of());

        tools.searchNews("  碳交易  ", 5);

        verify(news).search("碳交易", 5);
    }

    @Test
    void asksForKeywordWhenBlank() {
        assertThat(tools.searchNews("  ", 5)).contains("请提供搜索关键词");
        assertThat(tools.searchNews(null, 5)).contains("请提供搜索关键词");

        verify(news, never()).search(anyString(), anyInt());
    }

    @Test
    void rejectsOutOfRangeLimitWithoutCallingUpstream() {
        assertThat(tools.searchNews("碳交易", 0)).contains("1 到 10");
        assertThat(tools.searchNews("碳交易", 11)).contains("1 到 10");
        assertThat(tools.getHeadlines("科技", -1)).contains("1 到 10");

        verify(news, never()).search(anyString(), anyInt());
        verify(news, never()).headlines(anyString(), anyInt());
    }

    @Test
    void explainsWhenNothingMatches() {
        when(news.search("欧盟碳边境调节机制", 5)).thenReturn(List.of());

        assertThat(tools.searchNews("欧盟碳边境调节机制", 5))
                .contains("没有找到与「欧盟碳边境调节机制」相关的新闻")
                .contains("换个更宽泛的关键词");
    }

    @Test
    void formatsHeadlinesForCanonicalCategory() {
        when(news.headlines("科技", 1)).thenReturn(List.of(
                new NewsItem("爱范儿发布新硬件", "https://example.com/c", "爱范儿", "科技",
                        Instant.parse("2026-11-06T04:00:00Z"), "", "")));
        when(news.sourceName()).thenReturn("Stub");

        String result = tools.getHeadlines("tech", 1);

        assertThat(result).contains("科技头条（最近 1 条）").contains("1. 爱范儿发布新硬件");
        verify(news).headlines("科技", 1);
    }

    @Test
    void rejectsUnknownCategoryWithSupportedList() {
        assertThat(tools.getHeadlines("体育", 5))
                .contains("不支持的分类「体育」")
                .contains("科技、财经、综合");

        verify(news, never()).headlines(anyString(), anyInt());
    }

    @Test
    void turnsUpstreamFailureIntoPlainText() {
        when(news.search("碳交易", 5)).thenThrow(new NewsUnavailableException("all feeds down"));

        assertThat(tools.searchNews("碳交易", 5)).isEqualTo("新闻源暂时不可用，请稍后再试。");
    }

    @Test
    void neverLeaksUnexpectedException() {
        when(news.headlines("科技", 5)).thenThrow(new IllegalStateException("boom"));

        assertThat(tools.getHeadlines("科技", 5)).isEqualTo("查询新闻时发生异常，请稍后再试。");
    }
}
