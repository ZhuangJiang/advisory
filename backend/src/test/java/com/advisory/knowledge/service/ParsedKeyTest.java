package com.advisory.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code parsedKeyFor} places the parsed Markdown next to the raw object; a root-level object key
 * (no slash) must behave like any other directory instead of becoming {@code doc.md/parsed.md}.
 */
class ParsedKeyTest {

    @Test
    void noSlashObjectKeyBehavesLikeRootLevelKey() {
        assertEquals("parsed.md", DocumentIngestWorker.parsedKeyFor("doc.md"));
    }

    @Test
    void siblingKeySharesDirectoryWithRawObject() {
        assertEquals("kb/1/abc/parsed.md", DocumentIngestWorker.parsedKeyFor("kb/1/abc/doc.md"));
    }
}
