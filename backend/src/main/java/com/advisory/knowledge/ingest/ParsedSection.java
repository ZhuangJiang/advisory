package com.advisory.knowledge.ingest;

/**
 * One heading and the raw Markdown body that belongs to it.
 *
 * @param level       heading level (1-6)
 * @param heading     heading text without the leading hashes
 * @param headingPath full path from the document root, e.g. {@code "A > B > C"}
 * @param content     raw Markdown between this heading and the next one
 */
public record ParsedSection(int level, String heading, String headingPath, String content) {

    public ParsedSection {
        heading = heading == null ? "" : heading;
        headingPath = headingPath == null ? "" : headingPath;
        content = content == null ? "" : content;
    }
}
