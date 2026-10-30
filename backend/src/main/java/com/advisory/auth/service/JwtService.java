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
 * Issues and validates HS256 access tokens via jjwt.
 *
 * <p>Claims: {@code sub} = user id, {@code role}, {@code mustChangePassword}. The secret must be at
 * least 32 bytes; otherwise the bean fails fast at startup instead of producing a weak token.</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;

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
     * Parses and verifies a token, returning the caller plus the {@code mustChangePassword} flag.
     *
     * @throws io.jsonwebtoken.JwtException        on signature/expiry/malformed failures
     * @throws java.lang.IllegalArgumentException on malformed claims
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
