package com.advisory.knowledge.ingest;

/**
 * Chunking parameters. Lengths are Unicode code points, so one Chinese character counts as one.
 *
 * @param minChars     below this a trailing chunk is considered an orphan and is merged if possible
 * @param maxChars     hard upper bound for prose chunks (atomic code blocks and tables may exceed it)
 * @param overlapRatio fraction of the previous chunk repeated at the start of the next chunk
 */
public record ChunkConfig(int minChars, int maxChars, double overlapRatio) {

    public static final ChunkConfig DEFAULT = new ChunkConfig(400, 800, 0.12);

    public ChunkConfig {
        if (minChars <= 0) {
            throw new IllegalArgumentException("minChars must be positive");
        }
        if (maxChars < minChars) {
            throw new IllegalArgumentException("maxChars must be >= minChars");
        }
        if (overlapRatio < 0 || overlapRatio >= 1) {
            throw new IllegalArgumentException("overlapRatio must be in [0, 1)");
        }
    }
}
