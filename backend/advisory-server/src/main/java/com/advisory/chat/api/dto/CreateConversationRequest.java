package com.advisory.chat.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/conversations} 的请求体。
 *
 * <p>新建的会话会锁定 Agent 的当前版本，之后修改 Agent 不会改变已有会话的行为。</p>
 */
public record CreateConversationRequest(@NotNull Long agentId, String title) {
}
