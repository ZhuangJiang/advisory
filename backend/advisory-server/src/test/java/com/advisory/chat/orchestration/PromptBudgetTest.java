package com.advisory.chat.orchestration;

import com.advisory.knowledge.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词预算：估算要偏保守，裁剪不能破坏 {@code [n]} 与 {@code citations[n-1]} 的对应关系。
 */
class PromptBudgetTest {

    @Test
    void chineseCountsOneTokenPerCharacterAndAsciiFourPerChar() {
        assertEquals(5, PromptBudget.estimateTokens("知识库检索"));
        assertEquals(1, PromptBudget.estimateTokens("abcd"));
    }

    @Test
    void estimationIsMonotonicAndHandlesNull() {
        assertEquals(0, PromptBudget.estimateTokens(null));
        assertEquals(0, PromptBudget.estimateTokens(""));
        assertTrue(PromptBudget.estimateTokens("这是一段中文证据") > PromptBudget.estimateTokens("abc"));
        assertTrue(PromptBudget.estimateTokens("a".repeat(400)) >= 100,
                "4 个 ASCII 字符约等于 1 token");
    }

    @Test
    void evidenceBudgetShrinksWithFixedPromptAndNeverDropsBelowTheFloor() {
        int roomy = PromptBudget.evidenceTokens(8192, 200);
        int tight = PromptBudget.evidenceTokens(8192, 3000);
        assertTrue(tight < roomy, "more fixed prompt leaves less room for evidence");
        assertEquals(320, PromptBudget.evidenceTokens(1024, 5000),
                "a tiny window still gets a minimum slice instead of an empty evidence block");
        assertEquals(roomy, PromptBudget.evidenceTokens(0, 200),
                "non-positive window falls back to the default");
    }

    @Test
    void truncationNeverSplitsSurrogatePairs() {
        String text = "a".repeat(10) + "😀😀😀😀";
        String truncated = PromptBudget.truncateToTokens(text, 3);
        assertTrue(truncated.endsWith("😀"), "only full code points are kept, never half an emoji");
        assertFalse(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1)));
    }

    @Test
    void numberedEvidenceKeepsOriginalIndexesAndAnnouncesOmissions() {
        List<RetrievedChunk> chunks = List.of(
                chunk(1, "向量检索使用余弦相似度。".repeat(80)),
                chunk(2, "关键词检索使用 pg_trgm。".repeat(80)),
                chunk(3, "混合检索用 RRF 融合两路结果。".repeat(80)));

        String rendered = PromptTemplates.numberedEvidence(chunks, 300);

        assertTrue(rendered.startsWith("[1] "), "the first snippet is always included");
        assertTrue(rendered.contains("已截断"), "an oversized snippet is truncated rather than dropped");
        assertTrue(rendered.contains("另有 2 个片段未列入"), "omissions are stated explicitly");
        assertFalse(rendered.contains("[2] "), "the budget was spent on the first snippet");
        assertTrue(PromptBudget.estimateTokens(rendered) < 420,
                "rendered evidence stays close to the budget, actual=" + PromptBudget.estimateTokens(rendered));
    }

    @Test
    void unlimitedBudgetRendersEverySnippetWithStableIndexes() {
        List<RetrievedChunk> chunks = List.of(chunk(1, "第一段"), chunk(2, "第二段"), chunk(3, "第三段"));

        String rendered = PromptTemplates.numberedEvidence(chunks);

        assertTrue(rendered.contains("[1] doc-1"));
        assertTrue(rendered.contains("[2] doc-2"));
        assertTrue(rendered.contains("[3] doc-3"));
        assertFalse(rendered.contains("未列入"));
    }

    private static RetrievedChunk chunk(long id, String content) {
        return new RetrievedChunk(id, 100L + id, 10L, 11L, "doc-" + id, "node-" + id, content, 0.5);
    }
}
