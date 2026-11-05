package com.advisory.chat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 会话标题：侧栏里那一行字，取自会话的第一个问题。
 *
 * <p>三条边界值得钉住：换行不能带进标题（侧栏是一行）、超长要截断并加省略号（否则一行被挤爆）、
 * 截断必须按码点（emoji 被劈开会变成乱码，且没法再拼回去）。</p>
 */
class ConversationTitlesTest {

    @Test
    void keepsShortQuestionsAsIs() {
        assertEquals("报销时限是多久", ConversationTitles.derive("报销时限是多久？"));
        assertEquals("碳排放核算边界怎么划定", ConversationTitles.derive("碳排放核算边界怎么划定？"));
    }

    @Test
    void flattensWhitespaceAndNewlines() {
        assertEquals("报销时限 是多久", ConversationTitles.derive("  报销时限\n是多久？  "));
        assertEquals("第一行 第二行", ConversationTitles.derive("第一行\n\n\r\n第二行"));
    }

    @Test
    void truncatesLongQuestionsOnCodePoints() {
        String title = ConversationTitles.derive("请结合公司低碳转型规划说明碳排放核算边界的划定方法以及排放因子的选取依据");

        assertEquals(ConversationTitles.MAX_CODE_POINTS + 1, title.codePointCount(0, title.length()));
        assertEquals("请结合公司低碳转型规划说明碳排放核算边界的划定方…", title);
    }

    @Test
    void neverSplitsASurrogatePair() {
        // 每个 emoji 占两个 char：按 char 切第 24 位会把最后一个 emoji 劈成半个代理对
        String title = ConversationTitles.derive("😀".repeat(30));

        assertEquals("😀".repeat(ConversationTitles.MAX_CODE_POINTS) + "…", title);
    }

    @Test
    void refusesToNameWhenTheQuestionCarriesNothing() {
        assertNull(ConversationTitles.derive(null));
        assertNull(ConversationTitles.derive("   "));
        assertNull(ConversationTitles.derive("？？？"));
        assertNull(ConversationTitles.derive("\n\n"));
    }
}
