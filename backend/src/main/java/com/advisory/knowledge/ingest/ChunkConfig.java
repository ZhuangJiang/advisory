package com.advisory.knowledge.ingest;

/**
 * 切片参数。长度一律按 Unicode 码点计算，因此一个汉字算一个字符，中文文档不会被切成半截。
 *
 * @param minChars     低于该长度时，末尾片段视为孤儿片段，尽可能并入前一片段
 * @param maxChars     正文片段的硬上限（代码块与表格是原子单位，允许超出）
 * @param overlapRatio 上一片段重复到下一片段开头的比例，用于保持上下文连续
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
