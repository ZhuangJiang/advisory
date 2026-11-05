package com.advisory.knowledge.domain;

/**
 * 文档入库的生命周期。库里存的是名字（{@code V1__init.sql} 对这些取值有 CHECK 约束）；这个枚举是
 * 「到底有哪些状态、哪些是终态」的唯一出处。
 *
 * <p>允许的迁移是线性的 {@code PENDING -> PARSING -> CHUNKING -> EMBEDDING -> READY}，另外任何非终态都可以
 * 转到 {@code FAILED}，任何未删除状态都可以转到 {@code DELETED}。迁移由比较并设置的 SQL 保证，
 * 这个枚举本身不做校验——枚举无法阻止并发写入，SQL 条件才可以。</p>
 */
public enum DocumentStatus {

    PENDING,
    PARSING,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED,
    DELETED;

    /** 判断是否为终态；终态不会再被入库 worker 拾取。 */
    public boolean isTerminal() {
        return this == READY || this == FAILED || this == DELETED;
    }

    /**
     * 解析库中存的状态名。
     *
     * <p>取到未知值说明数据本身出了问题（不是客户端的错），因此抛出的是校验型异常而非默认值兜底——
     * 悄悄返回一个默认状态会让脏数据继续流动。</p>
     *
     * @param name 状态名
     * @return 对应枚举
     */
    public static DocumentStatus from(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("数据库中出现了未知的文档状态：" + raw, e);
        }
    }
}
