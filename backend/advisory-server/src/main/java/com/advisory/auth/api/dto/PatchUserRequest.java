package com.advisory.auth.api.dto;

/**
 * {@code PATCH /api/users/{id}} 的局部更新请求。
 *
 * <p>两个字段都可为空，{@code null} 表示该项不变；{@code password} 非空即视为一次重置。</p>
 *
 * @param enabled  是否启用账号
 * @param password 新密码明文，仅在重置密码时传入
 */
public record PatchUserRequest(Boolean enabled, String password) {
}
