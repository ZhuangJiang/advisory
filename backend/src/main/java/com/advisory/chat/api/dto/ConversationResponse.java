package com.advisory.chat.api.dto;

import com.advisory.chat.domain.Conversation;

import java.time.Instant;

/**
 * 侧边栏会话列表中的一行，{@code agentVersion} 是该会话锁定的版本。
 *
 * <p>暴露 {@code userId} 只有一个原因：管理员的列表不做过滤（spec §8），前端必须能识别出哪些行不属于自己，
 * 从而渲染成只读——否则用户会在一个注定被服务端 403 拒绝的输入框里白打一段字。</p>
 */
public record ConversationResponse(long id, long agentId, String agentName, int agentVersion,
                                   long userId, String title, Instant createdAt, Instant updatedAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(conversation.id(), conversation.agentId(),
                conversation.agentName(), conversation.agentVersion(), conversation.userId(),
                conversation.title(), conversation.createdAt(), conversation.updatedAt());
    }
}
