package com.advisory.chat.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词路由的边界：宁可漏判（多走一次检索），也不要误判（拿天气答案盖掉业务问题）。
 */
class ToolRoutingTest {

    @Test
    void recognisesWeatherQuestions() {
        assertTrue(ToolRouting.wantsLiveData("北京今天天气怎么样？"));
        assertTrue(ToolRouting.wantsLiveData("明天会下雨吗"));
        assertTrue(ToolRouting.wantsLiveData("杭州未来三天气温"));
        assertTrue(ToolRouting.wantsLiveData("上海这周有台风吗"));
        assertTrue(ToolRouting.wantsLiveData("what's the weather in Paris?"));
        assertTrue(ToolRouting.wantsLiveData("weather forecast for Tokyo"));
    }

    @Test
    void leavesBusinessQuestionsAlone() {
        assertFalse(ToolRouting.wantsLiveData("碳排放核算边界怎么划定？"));
        assertFalse(ToolRouting.wantsLiveData("核查流程有哪些步骤"));
        assertFalse(ToolRouting.wantsLiveData("报告里的排放因子从哪来"));
    }

    /**
     * 「温度」「湿度」这类词在设备与工艺文档里同样常见，刻意不在词表里：
     * 拿天气答案回答「炉膛温度控制范围」比漏判严重得多。
     */
    @Test
    void ambiguousTermsAreDeliberatelyExcluded() {
        assertFalse(ToolRouting.wantsLiveData("炉膛温度控制在多少"));
        assertFalse(ToolRouting.wantsLiveData("车间湿度要求是多少"));
    }

    @Test
    void handlesBlankInput() {
        assertFalse(ToolRouting.wantsLiveData(null));
        assertFalse(ToolRouting.wantsLiveData("   "));
    }
}
