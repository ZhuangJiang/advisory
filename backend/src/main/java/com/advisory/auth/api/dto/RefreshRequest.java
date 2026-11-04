package com.advisory.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新或登出请求，两者都只需要一个 refresh token。
 *
 * @param refreshToken 客户端持有的 refresh token 原文
 */
public record RefreshRequest(
        @NotBlank(message = "refreshToken must not be blank") String refreshToken) {
}
