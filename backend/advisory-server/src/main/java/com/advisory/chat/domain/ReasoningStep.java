package com.advisory.chat.domain;

import com.advisory.chat.orchestration.Phase;

import java.time.Instant;

/**
 * {@code reasoning_steps} 表的一行。
 *
 * <p>表里没有 {@code summary} 列，因此编排器写步骤时把摘要塞进
 * {@code payload = {"summary": ..., "detail": <步骤 JSON 的字符串>}}：这样不必加一次迁移，就能让
 * 人读的一行摘要与机器读的载荷待在同一处；{@code detail} 保持字符串类型，则保证即使内容不是合法 JSON
 * 也不会让插入失败。</p>
 */
public record ReasoningStep(long id, long messageId, int round, Phase phase, String summary,
                            String payload, long latencyMs, Instant createdAt) {
}
