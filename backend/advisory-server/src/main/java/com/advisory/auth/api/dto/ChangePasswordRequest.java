package com.advisory.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 自助改密请求。
 *
 * <p>只校验非空，长度与强度规则由 {@code AuthService} 之外的密码策略负责，此处不重复。</p>
 *
 * @param oldPassword 当前密码，用于二次确认身份
 * @param newPassword 新密码，不得与旧密码相同
 */
public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空") String newPassword) {
}
