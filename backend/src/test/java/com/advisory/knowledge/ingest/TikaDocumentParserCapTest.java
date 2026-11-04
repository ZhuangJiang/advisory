package com.advisory.knowledge.ingest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TikaDocumentParser} 文本截断上限的边界情形：无论从哪一侧算，截断位置都绝不能落在
 * 一对代理项（surrogate pair）的中间。
 */
class TikaDocumentParserCapTest {

    /** 截断点正落在高位代理项上时，既不能让它落单，也不能把它后面的低位代理项留下。 */
    @Test
    void capDoesNotSplitSurrogatePairAtCutPoint() {
        // "a" + 😀（占 2 个 char）+ "b"：按 char 下标 2 切正好落在高低代理项之间。
        String text = "a\uD83D\uDE00b";
        assertThat(text.length()).isEqualTo(4);

        String capped = TikaDocumentParser.cap(text, 2);

        // 切点必须往前退到代理对之前，绝不能落在它中间。
        assertThat(capped.codePoints().noneMatch(cp ->
                cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)).isTrue();
        assertThat(new String(capped.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(capped);
        assertThat(capped).isIn("a", "a\uD83D\uDE00");
    }

    /** 上限小于文本长度且不涉及代理对时，按位精确截断。 */
    @Test
    void capTruncatesOrdinaryTextExactly() {
        assertThat(TikaDocumentParser.cap("abcde", 3)).isEqualTo("abc");
    }

    /** 上限等于或大于长度时不截断。 */
    @Test
    void capKeepsShortTextWhole() {
        assertThat(TikaDocumentParser.cap("abc", 10)).isEqualTo("abc");
    }

    /** 不感知 Unicode 的上限：3 个码点、其中一个是辅助平面字符，结果须保持完整。 */
    @Test
    void capKeepsAstralCharactersWhole() {
        String text = "a😀😀b";
        assertThat(text.codePointCount(0, text.length())).isEqualTo(4);

        assertThat(TikaDocumentParser.cap(text, 3)).isEqualTo("a😀😀");
    }
}
