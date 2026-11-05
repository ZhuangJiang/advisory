package com.advisory.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code parsedKeyFor} 把解析出的 Markdown 放在原始对象旁边；根层级的对象键（不含斜杠）也必须按
 * 目录处理，而不能拼成 {@code doc.md/parsed.md} 这种把文件名当目录的结果。
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
