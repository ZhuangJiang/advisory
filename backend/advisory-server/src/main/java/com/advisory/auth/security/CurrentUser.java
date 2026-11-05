package com.advisory.auth.security;

import com.advisory.auth.domain.UserRole;

/**
 * 已通过认证的调用者，由 JWT 还原并作为安全上下文的主体。
 *
 * <p>只保留鉴权必需的字段，不查库、不含密码等敏感信息，因此每个请求都能零成本构造。</p>
 *
 * @param id   用户主键
 * @param role 平台角色
 */
public record CurrentUser(Long id, UserRole role) {
}
