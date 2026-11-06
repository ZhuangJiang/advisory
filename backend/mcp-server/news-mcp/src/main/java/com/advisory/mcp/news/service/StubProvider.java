package com.advisory.mcp.news.service;

import com.advisory.mcp.news.model.NewsItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线桩数据：不访问公网，供本地开发与自动化测试使用（NEWS_PROVIDER=stub）。
 *
 * <p>标题里刻意埋了中文关键词（新能源、碳交易、人工智能…），这样「搜关键词」的测试
 * 不需要联网也能断言命中；时间按小时递减，排序与「最新几条」的断言才稳定。</p>
 */
@Component
public class StubProvider implements NewsProvider {

    private static final List<String[]> SAMPLES = List.of(
            new String[]{"科技", "新能源电池回收技术取得突破，多家企业启动中试线"},
            new String[]{"财经", "全国碳市场今日成交额创近三个月新高"},
            new String[]{"综合", "多部门联合发布绿色低碳发展年度报告"},
            new String[]{"科技", "人工智能辅助碳排放核算工具通过第三方验证"},
            new String[]{"财经", "碳交易价格连续三日上涨，机构提示关注履约周期"},
            new String[]{"综合", "长三角区域协同推进重点行业节能降碳"}); 

    @Override
    public String sourceName() {
        return "Stub";
    }

    @Override
    public List<NewsItem> items() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS);
        List<NewsItem> items = new ArrayList<>(SAMPLES.size());
        for (int i = 0; i < SAMPLES.size(); i++) {
            String[] sample = SAMPLES.get(i);
            items.add(new NewsItem(
                    sample[1],
                    "https://example.com/news/" + (i + 1),
                    "Stub 新闻",
                    sample[0],
                    base.minus(i, ChronoUnit.HOURS),
                    "这是一条离线桩数据，仅用于开发与测试。",
                    "这是一条离线桩数据，仅用于开发与测试。"));
        }
        return items;
    }
}
