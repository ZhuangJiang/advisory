package com.advisory.mcp.news.service;

import com.advisory.mcp.news.model.NewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排序/去重/筛选的纯函数测试：这部分最容易在细节上出错，而不是在编译期被发现。
 */
class NewsRankingTest {

    private static NewsItem item(String title, String category, String time) {
        return item(title, category, time, "");
    }

    /** content 只参与匹配、不参与展示：用它模拟「标题不含关键词但正文有」的真实情况。 */
    private static NewsItem item(String title, String category, String time, String content) {
        return new NewsItem(title, "https://example.com/" + title.hashCode(), "测试源", category,
                time == null ? null : Instant.parse(time), "", content);
    }

    private static final List<NewsItem> ITEMS = List.of(
            item("新能源电池回收技术取得突破", "科技", "2026-11-06T02:00:00Z"),
            item("新能源汽车销量创新高", "财经", "2026-11-06T05:00:00Z"),
            item("新能源「电池回收」技术取得突破", "科技", "2026-11-06T04:00:00Z"),
            item("今天天气不错", "综合", null));

    @Test
    void searchesByKeywordAndKeepsNewestFirst() {
        List<NewsItem> hits = NewsRanking.search(ITEMS, "新能源", 10);

        assertThat(hits).extracting(NewsItem::title)
                .containsExactly("新能源汽车销量创新高", "新能源「电池回收」技术取得突破");
    }

    @Test
    void requiresAllTokensBeforeFallingBackToPartialMatch() {
        // 两个词都命中时按 AND 精确匹配
        assertThat(NewsRanking.search(ITEMS, "新能源 销量", 10)).hasSize(1);

        // AND 无结果时退化为 OR，并把命中词多的排前面——直接返回空对用户没有价值
        List<NewsItem> partial = NewsRanking.search(ITEMS, "新能源 碳交易", 10);
        assertThat(partial).isNotEmpty();
        assertThat(partial.get(0).title()).contains("新能源");
    }

    @Test
    void deduplicatesRepostedTitlesWhileKeepingNewest() {
        List<NewsItem> hits = NewsRanking.search(ITEMS, "电池回收", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).publishedAt()).isEqualTo(Instant.parse("2026-11-06T04:00:00Z"));
    }

    @Test
    void entriesWithoutTimeSortLast() {
        List<NewsItem> hits = NewsRanking.search(ITEMS, "天气", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).hasTime()).isFalse();
    }

    @Test
    void respectsLimit() {
        assertThat(NewsRanking.search(ITEMS, "新能源", 1)).hasSize(1);
    }

    @Test
    void blankKeywordMatchesNothing() {
        assertThat(NewsRanking.search(ITEMS, "   ", 10)).isEmpty();
    }

    @Test
    void headlinesFilterByCanonicalCategory() {
        // 科技分类下有两条同题新闻（转载），去重后只剩一条，保留较新的那条
        List<NewsItem> tech = NewsRanking.headlines(ITEMS, "科技", 10);
        assertThat(tech).hasSize(1);
        assertThat(tech.get(0).publishedAt()).isEqualTo(Instant.parse("2026-11-06T04:00:00Z"));

        assertThat(NewsRanking.headlines(ITEMS, "tech", 10)).hasSize(1);
        assertThat(NewsRanking.headlines(ITEMS, "财经", 10)).hasSize(1);
    }

    @Test
    void unknownCategoryIsRejectedAndReported() {
        assertThat(NewsRanking.isSupportedCategory("体育")).isFalse();
        assertThat(NewsRanking.headlines(ITEMS, "体育", 10)).isEmpty();
        assertThat(NewsRanking.isSupportedCategory(null)).isTrue();
        assertThat(NewsRanking.canonicalCategory("  ")).isEqualTo("综合");
    }

    @Test
    void knownCategoriesAreTheThreeDocumentedOnes() {
        assertThat(NewsRanking.knownCategories()).containsExactlyInAnyOrder("科技", "财经", "综合");
    }

    /** 模型经常把「新闻/最新的/资讯」一起塞进关键词，按 AND 匹配会让结果直接归零。 */
    @Test
    void ignoresFillerWordsAppendedToTheKeyword() {
        assertThat(NewsRanking.search(ITEMS, "新能源 新闻", 10)).hasSize(2);
        assertThat(NewsRanking.search(ITEMS, "新能源 最新的 相关 资讯", 10)).hasSize(2);
    }

    /** 多词过窄时退化为「命中任意词」，而不是直接空手而归。 */
    @Test
    void fallsBackToPartialMatchWhenAllTokensAreTooNarrow() {
        assertThat(NewsRanking.search(ITEMS, "新能源 碳交易", 10)).isNotEmpty();
    }

    /** RSS 标题常不含关键词、正文里却有：只按标题匹配会漏掉大量真实结果。 */
    @Test
    void matchesKeywordAppearingOnlyInContent() {
        List<NewsItem> items = List.of(
                item("某企业发布年度报告", "财经", "2026-11-06T01:00:00Z", "报告中提到碳捕集技术的商业化进展"));

        assertThat(NewsRanking.search(items, "碳捕集", 10)).hasSize(1);
    }
}
