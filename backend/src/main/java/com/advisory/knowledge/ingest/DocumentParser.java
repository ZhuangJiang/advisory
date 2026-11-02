package com.advisory.knowledge.ingest;

import java.io.InputStream;

/**
 * Turns an uploaded document into Markdown-ish text plus its heading sections.
 *
 * <p>Implementations must never log file contents: documents may contain sensitive material.</p>
 */
public interface DocumentParser {

    /**
     * @param in       document bytes; the caller owns the stream
     * @param filename original filename, used for the extension and Tika detection hints
     * @return parsed Markdown and heading sections
     * @throws com.advisory.common.error.AppException with {@code VALIDATION_FAILED} when the file
     *         cannot be parsed or produces no text
     */
    ParsedDocument parse(InputStream in, String filename);
}
