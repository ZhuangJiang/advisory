package com.advisory.audit.dto;

import java.time.Instant;

/**
 * 管理员审计列表中的一条会话。
 *
 * <p>有意做成反范式：审计页是一张平铺的表格，而「让前端自己去关联用户、Agent 与消息」在现有 API 下
 * 根本做不到。{@code messageCount} 与 {@code lastMessageAt} 由 SQL 从 {@code messages} 聚合而来；
 * 尚未产生回答的会话同样会出现，只是 {@code lastMessageAt} 为空。</p>
 */
public record AuditConversationRow(long id, long userId, String userName, long agentId,
                                   String agentName, int agentVersion, String title,
                                   long messageCount, Instant createdAt, Instant lastMessageAt) {
}
