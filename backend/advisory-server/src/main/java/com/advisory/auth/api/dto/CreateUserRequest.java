package com.advisory.auth.api.dto;

import com.advisory.auth.domain.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 管理员新建用户的请求体。
 *
 * @param username 登录名，需全局唯一
 * @param password 初始密码，落库前由 BCrypt 加密
 * @param role     平台角色
 */
public record CreateUserRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        @NotNull(message = "必须指定角色") UserRole role) {
}
