package com.advisory.auth.security;

/**
 * 校验通过的 JWT 内容：已认证的 {@link CurrentUser}，加上 {@code mustChangePassword} 标记。
 *
 * <p>把标记一并带出来，过滤器才能在路由之前就拦下需要先改密的请求。</p>
 *
 * @param user               调用者身份
 * @param mustChangePassword 是否仍需强制修改密码
 */
public record ParsedToken(CurrentUser user, boolean mustChangePassword) {
}
