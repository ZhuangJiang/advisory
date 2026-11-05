package com.advisory.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 平台用户（表 {@code users}）。
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto} 为 {@code validate}，因此列定义必须与 {@code V1__init.sql}
 * 完全一致，改名或改类型会直接导致启动失败。{@code created_at} 在库里有默认值，用
 * {@link CreationTimestamp} 让 Hibernate 在插入时显式赋值，避免发送 {@code NULL} 覆盖默认值。</p>
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录名，全局唯一。 */
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** BCrypt 密码哈希，{@code length = 255} 为后续更换算法留出空间。 */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 平台角色，按名称存字符串，便于在库里直接阅读。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private UserRole role;

    /** 预留的租户标识，当前版本未启用。 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** 是否启用；禁用后登录与刷新都会被拒绝。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 是否仍需强制修改密码；新建与被重置密码的账号默认为真。 */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = true;

    /** 创建时间，由 Hibernate 在插入时填充。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 需要的无参构造，业务代码不应使用。 */
    protected User() {
        // 供 JPA 使用
    }

    /**
     * @param username     登录名
     * @param passwordHash 已加密的密码哈希，明文不得传进来
     * @param role         平台角色
     */
    public User(String username, String passwordHash, UserRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
