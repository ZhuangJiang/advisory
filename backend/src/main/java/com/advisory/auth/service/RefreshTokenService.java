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
 * Issues opaque refresh tokens. Only the SHA-256 hex of the token is persisted; the raw value never
 * touches the database.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RAW_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final Duration refreshTtl;

    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${app.jwt.refresh-ttl}") Duration refreshTtl) {
        this.repository = repository;
        this.refreshTtl = refreshTtl;
    }

    /**
     * Generates a fresh refresh token for the user and persists its hash.
     *
     * @return the raw token to return to the client
     */
    public String issue(Long userId) {
        byte[] bytes = new byte[RAW_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(userId, hash(raw), Instant.now().plus(refreshTtl)));
        return raw;
    }

    public String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
