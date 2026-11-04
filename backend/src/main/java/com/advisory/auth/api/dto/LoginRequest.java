package com.advisory.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 账号密码登录请求。
 *
 * @param username 登录名
 * @param password 明文密码，仅用于与库中哈希比对
 */
public record LoginRequest(
        @NotBlank(message = "username must not be blank") String username,
        @NotBlank(message = "password must not be blank") String password) {
}
