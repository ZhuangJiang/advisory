package com.advisory.auth.domain;

/**
 * 平台级角色，按名称字符串存入 {@code users.role}（库中有 CHECK 约束）。
 *
 * <p>Spring Security 的权限名由枚举名加 {@code ROLE_} 前缀得到，因此 {@code hasRole('ADMIN')}
 * 这类表达式才能直接生效。</p>
 */
public enum UserRole {

    /** 管理员：可管理用户、提供商、Agent，并查看全部会话审计。 */
    ADMIN,

    /** 普通用户：只能使用对话与知识库，且仅能看到自己的会话。 */
    USER
}
