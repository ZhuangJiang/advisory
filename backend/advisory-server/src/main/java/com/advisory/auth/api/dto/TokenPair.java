package com.advisory.auth.api.dto;

/**
 * 下发给客户端的令牌对。
 *
 * <p>access token 短期有效且无状态，refresh token 长期有效但每次使用都会轮换。</p>
 *
 * @param accessToken  访问令牌，放在 {@code Authorization: Bearer} 头中
 * @param refreshToken 刷新令牌，仅用于 {@code /refresh} 与 {@code /logout}
 */
public record TokenPair(String accessToken, String refreshToken) {
}
