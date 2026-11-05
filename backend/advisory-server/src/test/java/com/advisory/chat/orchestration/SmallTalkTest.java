package com.advisory.chat.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寒暄识别：判定必须能拦住「你好」这类输入，同时绝不能吞掉带正题的问候。
 *
 * <p>误判的代价不对称：漏判只是继续走一次正常检索（慢一点、结果仍然正确），误判则会把一个真问题
 * 答成一句「你好，我是…」。因此这里重点覆盖「以寒暄开头、后面跟着真问题」的用例。</p>
 */
class SmallTalkTest {

    @Test
    void recognisesPureGreetingsAndMetaQuestions() {
        assertTrue(SmallTalk.isSmallTalk("你好"));
        assertTrue(SmallTalk.isSmallTalk("您好！"));
        assertTrue(SmallTalk.isSmallTalk("你好呀"));
        assertTrue(SmallTalk.isSmallTalk("你好，谢谢"));
        assertTrue(SmallTalk.isSmallTalk("谢谢啦"));
        assertTrue(SmallTalk.isSmallTalk("在吗"));
        assertTrue(SmallTalk.isSmallTalk("你是谁"));
        assertTrue(SmallTalk.isSmallTalk("你能做什么？"));
        assertTrue(SmallTalk.isSmallTalk("hello"));
        assertTrue(SmallTalk.isSmallTalk("Hello!"));
        assertTrue(SmallTalk.isSmallTalk("ok"));
        assertTrue(SmallTalk.isSmallTalk("  嗯  "));
    }

    @Test
    void keepsQuestionsThatOnlyStartWithAGreeting() {
        assertFalse(SmallTalk.isSmallTalk("你好，碳排放核算边界怎么划定？"));
        assertFalse(SmallTalk.isSmallTalk("你好碳排放"));
        assertFalse(SmallTalk.isSmallTalk("谢谢，那排放因子怎么取"));
        assertFalse(SmallTalk.isSmallTalk("好的，那核算边界呢"));
    }

    @Test
    void keepsOrdinaryQuestions() {
        assertFalse(SmallTalk.isSmallTalk("碳排放核算边界"));
        assertFalse(SmallTalk.isSmallTalk("双碳是什么"));
        assertFalse(SmallTalk.isSmallTalk("在线文档"));
        assertFalse(SmallTalk.isSmallTalk("okr 怎么定"));
        assertFalse(SmallTalk.isSmallTalk(""));
        assertFalse(SmallTalk.isSmallTalk(null));
    }
}
