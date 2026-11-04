package com.advisory.chat.domain;

/**
 * 助手消息的生命周期（{@code messages.status}）。
 *
 * <p>{@code PENDING} 从 SSE 请求被接受那一刻起就存在（此时还没调用模型），收到第一个增量后转为
 * {@code STREAMING}；三个终态分别是 {@code COMPLETED}、{@code FAILED}（提供商报错，不重试）与
 * {@code TRUNCATED}（超时或连续两次结构化输出无法解析后产出的不完整答案）。流上的 {@code done} 只会带
 * 最后这三个状态，中间态不会外泄。</p>
 */
public enum MessageStatus {
    PENDING,
    STREAMING,
    COMPLETED,
    FAILED,
    TRUNCATED
}
