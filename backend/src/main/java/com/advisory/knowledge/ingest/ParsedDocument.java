package com.advisory.knowledge.ingest;

import java.util.List;

/**
 * Result of parsing an uploaded document.
 *
 * @param markdown normalized Markdown-ish text; headings are preserved as ATX headings
 * @param sections heading sections in document order
 */
public record ParsedDocument(String markdown, List<ParsedSection> sections) {

    public ParsedDocument {
        markdown = markdown == null ? "" : markdown;
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
