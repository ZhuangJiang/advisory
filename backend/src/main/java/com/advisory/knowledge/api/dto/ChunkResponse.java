package com.advisory.knowledge.api.dto;

/**
 * One stored chunk as returned by {@code GET /api/documents/{id}/chunks}.
 *
 * <p>Deliberately excludes the embedding: 1024 floats per chunk are of no use to the UI and would
 * dominate the payload. {@code headingPath} is the section trail the chunker produced
 * ({@code "A > B > C"}), or an empty string for documents without headings (V3 stores it).</p>
 */
public record ChunkResponse(
        Long id,
        int seq,
        String headingPath,
        String content,
        Integer tokenCount) {
}
