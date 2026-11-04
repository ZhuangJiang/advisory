package com.advisory.auth.service;

import com.advisory.auth.domain.UserRole;
import com.advisory.auth.security.CurrentUser;
import com.advisory.auth.security.ParsedToken;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 基于 jjwt 签发与校验 HS256 access token。
 *
 * <p>载荷约定：{@code sub} 为用户主键，{@code role} 为角色名，{@code mustChangePassword} 为强制改密标记。
 * 密钥不足 32 字节时在启动阶段直接抛错，而不是签发弱令牌后才发现问题。</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;

    /**
     * @param secret    签名密钥，UTF-8 编码后至少 32 字节
     * @param accessTtl access token 有效期
     * @throws IllegalStateException 密钥长度不足时抛出，使应用启动即失败
     */
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-ttl}") Duration accessTtl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes for HS256; got " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = accessTtl;
    }

    /**
     * 签发 access token。
     *
     * @param userId             用户主键
     * @param role               平台角色
     * @param mustChangePassword 是否仍需强制修改密码
     * @return 紧凑格式的 JWT
     */
    public String issueAccessToken(Long userId, UserRole role, boolean mustChangePassword) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role.name())
                .claim("mustChangePassword", mustChangePassword)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析令牌，返回调用者身份与强制改密标记。
     *
     * @param token 紧凑格式的 JWT
     * @return 解析结果
     * @throws io.jsonwebtoken.JwtException        签名不匹配、已过期或格式非法时抛出
     * @throws java.lang.IllegalArgumentException 载荷缺少 {@code role} 等必需声明时抛出
     */
    public ParsedToken parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Long id = Long.valueOf(claims.getSubject());
        String roleName = claims.get("role", String.class);
        if (roleName == null) {
            throw new IllegalArgumentException("JWT is missing the 'role' claim");
        }
        UserRole role = UserRole.valueOf(roleName);
        boolean mustChangePassword = Boolean.TRUE.equals(claims.get("mustChangePassword", Boolean.class));
        return new ParsedToken(new CurrentUser(id, role), mustChangePassword);
    }
}
