package com.advisory.provider.domain;

/**
 * {@code models} 表中一行的用途，以 {@code VARCHAR(16)} 存储，{@code V1__init.sql} 中有 CHECK 约束。
 */
public enum ModelCapability {

    /** 对话模型，供 Agent 的推理与作答使用。 */
    CHAT,

    /** 向量模型，供知识库切片入库与检索使用；必须声明维度。 */
    EMBEDDING
}
