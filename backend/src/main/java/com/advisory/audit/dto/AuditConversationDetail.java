package com.advisory.audit.dto;

import com.advisory.chat.api.dto.MessageDetailResponse;

import java.util.List;

/**
 * {@code GET /api/admin/conversations/{id}} 的返回体：一条会话及其全部判定依据——会话头信息，
 * 以及每条消息连同其落库的推理步骤与引用。
 *
 * <p>结构上就是只读的：审计模块没有任何写接口，管理员无法替用户作答，也无法修改历史（spec §8）。</p>
 */
public record AuditConversationDetail(AuditConversationRow conversation,
                                      List<MessageDetailResponse> messages) {
}
