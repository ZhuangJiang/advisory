package com.advisory.mcp.news.client;

import com.advisory.mcp.news.config.NewsProperties;
import com.advisory.mcp.news.model.NewsItem;
import com.advisory.mcp.news.support.NewsUnavailableException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 解析器的契约：脏数据要清洗而不是抛给下游，反爬页要给出可定位的错误。
 */
class RssParserTest {

    private final RssParser parser = new RssParser();
    private final NewsProperties.Feed feed =
            new NewsProperties.Feed("测试源", "https://example.com/rss", "科技");

    @Test
    void parsesTitlesLinksAndTimes() throws IOException {
        List<NewsItem> items = parser.parse(feed, sample());

        assertThat(items).hasSize(2);
        NewsItem first = items.get(0);
        assertThat(first.title()).isEqualTo("新能源电池回收技术取得突破");
        assertThat(first.link()).isEqualTo("https://example.com/a");
        assertThat(first.source()).isEqualTo("测试源");
        assertThat(first.category()).isEqualTo("科技");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-11-06T04:00:00Z"));
    }

    @Test
    void stripsHtmlFromSummary() throws IOException {
        NewsItem first = parser.parse(feed, sample()).get(0);

        assertThat(first.summary())
                .doesNotContain("<p>")
                .doesNotContain("<b>")
                .contains("多家企业启动")
                .contains("中试线");
    }

    @Test
    void keepsEntriesWithoutTimeButDropsBlankTitles() throws IOException {
        List<NewsItem> items = parser.parse(feed, sample());

        assertThat(items).extracting(NewsItem::title).doesNotContain("");
        assertThat(items.get(1).title()).isEqualTo("没有发布时间的新闻");
        assertThat(items.get(1).hasTime()).isFalse();
    }

    @Test
    void reportsHtmlPageAsUnavailableInsteadOfCrashing() {
        byte[] html = "<!DOCTYPE html><html><body>反爬</body></html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(feed, html))
                .isInstanceOf(NewsUnavailableException.class)
                .hasMessageContaining("https://example.com/rss");
    }

    @Test
    void rejectsEmptyPayload() {
        assertThatThrownBy(() -> parser.parse(feed, new byte[0]))
                .isInstanceOf(NewsUnavailableException.class);
    }

    @Test
    void cleanCollapsesWhitespaceAndEntities() {
        assertThat(RssParser.clean("  <b>标题</b>&nbsp;测试\n 换行 "))
                .isEqualTo("标题 测试 换行");
    }

    private byte[] sample() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/sample-rss.xml")) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        }
    }
}
