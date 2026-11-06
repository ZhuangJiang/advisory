package com.advisory.mcp.news.service;

import com.advisory.mcp.news.model.NewsItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 新闻的排序、去重与筛选。全部是纯函数，便于直接单测——这部分逻辑最容易出错
 * （时间缺失、同一新闻被多个源转载、关键词大小写、模型传了「新能源 新闻」这种带填充词的查询）。
 */
public final class NewsRanking {

    private static final Map<String, String> CATEGORY_ALIASES = Map.ofEntries(
            Map.entry("科技", "科技"), Map.entry("技术", "科技"), Map.entry("数码", "科技"),
            Map.entry("tech", "科技"), Map.entry("technology", "科技"),
            Map.entry("财经", "财经"), Map.entry("经济", "财经"), Map.entry("金融", "财经"),
            Map.entry("finance", "财经"), Map.entry("business", "财经"), Map.entry("economy", "财经"),
            Map.entry("综合", "综合"), Map.entry("要闻", "综合"), Map.entry("头条", "综合"),
            Map.entry("国内", "综合"), Map.entry("general", "综合"), Map.entry("headlines", "综合"));

    /**
     * 查询里的填充词：模型很喜欢把「新闻/资讯/最新/相关」一起塞进关键词。
     * 这些词在标题里几乎不出现，按 AND 匹配会让结果直接归零——先剔掉它们再匹配。
     */
    private static final Set<String> FILLER_TOKENS = Set.of(
            "新闻", "资讯", "消息", "动态", "报道", "头条", "快讯", "要闻", "相关", "最新", "最近",
            "今天", "今日", "近期", "news", "latest", "today", "headline", "headlines");

    /** 时间缺失的条目排最后：它们不代表「最新」，只是源没给时间。 */
    private static final Comparator<NewsItem> BY_TIME_DESC = (left, right) -> {
        if (left.publishedAt() == null && right.publishedAt() == null) {
            return 0;
        }
        if (left.publishedAt() == null) {
            return 1;
        }
        if (right.publishedAt() == null) {
            return -1;
        }
        return right.publishedAt().compareTo(left.publishedAt());
    };

    private NewsRanking() {
    }

    /** 分类是否可识别；空白视为「综合」，由调用方决定默认值。 */
    public static boolean isSupportedCategory(String raw) {
        return canonicalCategory(raw) != null;
    }

    /** 把别名（tech/技术/要闻…）归一成配置里使用的分类名；无法识别时返回 null。 */
    public static String canonicalCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "综合";
        }
        return CATEGORY_ALIASES.get(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static Set<String> knownCategories() {
        return Set.of("科技", "财经", "综合");
    }

    /**
     * 关键词搜索。
     *
     * <p>匹配范围是标题 + 摘要 + 正文全文：RSS 的标题常常不含关键词，正文里却有；
     * 只按标题匹配会漏掉大量真实结果（实测「新能源」在某源里标题 0 条、正文多处出现）。</p>
     *
     * <p>优先级：先按「全部词命中」过滤；若没有结果且词数大于 1，再退化为「命中任意词」并按命中词数排序。
     * 这一步是为了「新能源 电池 回收」这类过窄查询不至于直接空手而归。</p>
     */
    public static List<NewsItem> search(List<NewsItem> items, String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        List<String> raw = tokenize(keyword);
        List<String> meaningful = new ArrayList<>(raw);
        meaningful.removeIf(FILLER_TOKENS::contains);
        // 全是填充词（例如只问「今天有什么新闻」）时退回原始词，交给下面的匹配逻辑处理
        final List<String> tokens = meaningful.isEmpty() ? raw : meaningful;
        if (tokens.isEmpty()) {
            return List.of();
        }

        List<NewsItem> allMatched = items.stream().filter(item -> hits(item, tokens) == tokens.size()).toList();
        if (!allMatched.isEmpty()) {
            return rank(allMatched, limit);
        }
        if (tokens.size() > 1) {
            List<NewsItem> anyMatched = items.stream().filter(item -> hits(item, tokens) > 0).toList();
            return rank(anyMatched, limit, tokens);
        }
        return List.of();
    }

    /** 分类头条：按分类过滤后按时间倒序；分类无法识别时返回空列表，由工具层提示可选分类。 */
    public static List<NewsItem> headlines(List<NewsItem> items, String category, int limit) {
        String canonical = canonicalCategory(category);
        if (canonical == null) {
            return List.of();
        }
        List<NewsItem> filtered = items.stream()
                .filter(item -> canonical.equals(item.category()))
                .toList();
        return rank(filtered, limit);
    }

    private static List<String> tokenize(String keyword) {
        List<String> tokens = new ArrayList<>();
        for (String token : keyword.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static int hits(NewsItem item, List<String> tokens) {
        String haystack = item.searchableText().toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : tokens) {
            if (haystack.contains(token)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * 去重 + 按时间排序 + 截断。去重用「去掉空白与标点后的标题」做键：
     * 同一新闻被不同源转载时标题几乎一致，加不加书名号、有没有空格却常有差异。
     */
    private static List<NewsItem> rank(List<NewsItem> items, int limit) {
        return dedupeAndLimit(items.stream().sorted(BY_TIME_DESC).toList(), limit);
    }

    /** OR 退化时的排序：命中词多的优先，同分再按时间。 */
    private static List<NewsItem> rank(List<NewsItem> items, int limit, List<String> tokens) {
        Comparator<NewsItem> byHitsThenTime = (left, right) -> {
            int compare = Integer.compare(hits(right, tokens), hits(left, tokens));
            return compare != 0 ? compare : BY_TIME_DESC.compare(left, right);
        };
        return dedupeAndLimit(items.stream().sorted(byHitsThenTime).toList(), limit);
    }

    private static List<NewsItem> dedupeAndLimit(List<NewsItem> sorted, int limit) {
        LinkedHashMap<String, NewsItem> unique = new LinkedHashMap<>();
        sorted.forEach(item -> unique.putIfAbsent(normalizeTitle(item.title()), item));
        return unique.values().stream().limit(Math.max(0, limit)).toList();
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }
}
