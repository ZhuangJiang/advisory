package com.advisory.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 持久化的 refresh token（表 {@code refresh_tokens}）。
 *
 * <p>只保存令牌的 SHA-256 十六进制摘要，明文永不落库：即使库被读取，也无法据此直接伪造会话。</p>
 *
 * <p>轮换时把行标记为已吊销（{@code revoked_at}）而不是物理删除，这样重放旧令牌在审计时仍可追溯，
 * 同时刷新调用照旧失败。</p>
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 令牌的 SHA-256 十六进制摘要，{@code length = 128} 为摘要留出余量。 */
    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    /** 过期时间，超过后不再接受刷新。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 签发时间，由 Hibernate 在插入时填充。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 吊销时间；为空表示仍然有效。 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** JPA 需要的无参构造，业务代码不应使用。 */
    protected RefreshToken() {
        // 供 JPA 使用
    }

    /**
     * @param userId    归属用户
     * @param tokenHash 令牌摘要
     * @param expiresAt 过期时间
     */
    public RefreshToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    /**
     * 标记为已吊销。只改内存状态，落库依赖调用方所在事务；幂等改写，重复调用不会报错。
     *
     * @param when 吊销时间
     */
    public void revoke(Instant when) {
        this.revokedAt = when;
    }
}
