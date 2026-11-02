package com.advisory.knowledge.ingest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases of {@link TikaDocumentParser}'s text cap: the truncation boundary must never fall
 * between the two halves of a surrogate pair, in either direction.
 */
class TikaDocumentParserCapTest {

    /** A cap landing on the high surrogate must not orphan it (or the low surrogate it precedes). */
    @Test
    void capDoesNotSplitSurrogatePairAtCutPoint() {
        // "a" + 😀 (2 chars) + "b": cutting at char index 2 lands between high and low surrogate.
        String text = "a\uD83D\uDE00b";
        assertThat(text.length()).isEqualTo(4);

        String capped = TikaDocumentParser.cap(text, 2);

        // The cut must move back to before the surrogate pair, never between its halves.
        assertThat(capped.codePoints().noneMatch(cp ->
                cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)).isTrue();
        assertThat(new String(capped.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(capped);
        assertThat(capped).isIn("a", "a\uD83D\uDE00");
    }

    /** A cap below the text length that does not touch surrogate pairs truncates exactly. */
    @Test
    void capTruncatesOrdinaryTextExactly() {
        assertThat(TikaDocumentParser.cap("abcde", 3)).isEqualTo("abc");
    }

    /** No truncation at or above the length. */
    @Test
    void capKeepsShortTextWhole() {
        assertThat(TikaDocumentParser.cap("abc", 10)).isEqualTo("abc");
    }

    /** Unicode-blind cap: 3 code points, one of them astral, stays complete. */
    @Test
    void capKeepsAstralCharactersWhole() {
        String text = "a😀😀b";
        assertThat(text.codePointCount(0, text.length())).isEqualTo(4);

        assertThat(TikaDocumentParser.cap(text, 3)).isEqualTo("a😀😀");
    }
}
