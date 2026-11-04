package com.advisory.audit.dto;

import com.advisory.chat.api.dto.MessageDetailResponse;

import java.util.List;

/**
 * {@code GET /api/admin/conversations/{id}} — one thread with everything needed to judge it:
 * the conversation header, and every message with its persisted steps and citations.
 *
 * <p>Read-only by construction: there is no write endpoint in the audit module, so an administrator
 * cannot answer on a user's behalf or edit history (spec §8).</p>
 */
public record AuditConversationDetail(AuditConversationRow conversation,
                                      List<MessageDetailResponse> messages) {
}
