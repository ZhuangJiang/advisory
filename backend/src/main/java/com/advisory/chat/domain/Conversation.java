package com.advisory.chat.domain;

import java.time.Instant;

/**
 * {@code conversations} 表的一行：用户与某个 Agent 之间的一条会话。
 *
 * @param agentVersion <em>创建会话时锁定的</em> Agent 版本。该会话中的每个问题都会重新读取这份快照，
 *                     因此之后修改 Agent 不会改变已有会话的行为与答案（spec §9）。
 *                     {@code agentName} 只为展示而关联，不参与任何逻辑
 */
public record Conversation(long id, long agentId, String agentName, int agentVersion, long userId,
                           String title, Instant createdAt, Instant updatedAt) {
}
