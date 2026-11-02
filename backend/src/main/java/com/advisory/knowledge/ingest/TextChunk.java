package com.advisory.knowledge.ingest;

/**
 * A chunk ready to be embedded.
 *
 * @param seq         0-based position in the document
 * @param headingPath {@code "A > B > C"} of the enclosing section, or {@code ""} when the document
 *                    has no headings
 * @param content     chunk text (may overlap the previous chunk)
 */
public record TextChunk(int seq, String headingPath, String content) {

    public TextChunk {
        headingPath = headingPath == null ? "" : headingPath;
        content = content == null ? "" : content;
    }
}
