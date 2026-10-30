package com.advisory.auth.api.dto;

/**
 * Partial update for {@code PATCH /api/users/{id}}. {@code enabled} and {@code password} are both
 * optional; {@code null} means "leave unchanged".
 */
public record PatchUserRequest(Boolean enabled, String password) {
}
