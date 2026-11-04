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
        @NotBlank(message = "username must not be blank") String username,
        @NotBlank(message = "password must not be blank") String password,
        @NotNull(message = "role must not be null") UserRole role) {
}
