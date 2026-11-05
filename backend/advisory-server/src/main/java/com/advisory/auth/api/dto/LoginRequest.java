package com.advisory.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 账号密码登录请求。
 *
 * @param username 登录名
 * @param password 明文密码，仅用于与库中哈希比对
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {
}
