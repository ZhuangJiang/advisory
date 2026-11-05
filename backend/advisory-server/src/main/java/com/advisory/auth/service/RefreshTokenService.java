package com.advisory.auth.service;

import com.advisory.auth.domain.RefreshToken;
import com.advisory.auth.repo.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 签发不透明 refresh token。
 *
 * <p>令牌本身是 32 字节安全随机数的 Base64URL 编码，不含任何可推断信息；落库只存 SHA-256 摘要，
 * 明文不落库，库被拖走也无法直接冒用。</p>
 */
@Service
public class RefreshTokenService {

    /** 密码学安全的随机源，令牌可预测等于会话可伪造。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 原始随机字节数，32 字节对应 256 位熵。 */
    private static final int RAW_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final Duration refreshTtl;

    /**
     * @param repository refresh token 仓储
     * @param refreshTtl refresh token 有效期
     */
    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${app.jwt.refresh-ttl}") Duration refreshTtl) {
        this.repository = repository;
        this.refreshTtl = refreshTtl;
    }

    /**
     * 为用户生成新的 refresh token 并持久化其摘要。
     *
     * @param userId 用户主键
     * @return 返回给客户端的令牌原文
     */
    public String issue(Long userId) {
        byte[] bytes = new byte[RAW_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(userId, hash(raw), Instant.now().plus(refreshTtl)));
        return raw;
    }

    /**
     * 计算令牌摘要。相同原文必然得到相同摘要，因此可直接用于等值查询。
     *
     * @param raw 令牌原文
     * @return SHA-256 的十六进制小写字符串
     */
    public String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
