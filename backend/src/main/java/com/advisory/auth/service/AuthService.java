package com.advisory.auth.service;

import com.advisory.auth.api.dto.TokenPair;
import com.advisory.auth.domain.RefreshToken;
import com.advisory.auth.domain.User;
import com.advisory.auth.repo.RefreshTokenRepository;
import com.advisory.auth.repo.UserRepository;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Login / refresh / logout / change-password orchestration.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       RefreshTokenService refreshTokenService,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    public TokenPair login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .filter(User::isEnabled)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "Invalid username or password"));
        return issuePair(user);
    }

    /**
     * Rotates a refresh token: the presented token is marked revoked and a fresh one is issued.
     * A replay of an already-rotated token is rejected with 401.
     */
    @Transactional
    public TokenPair refresh(String rawToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(refreshTokenService.hash(rawToken))
                .filter(t -> t.getRevokedAt() == null)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "Invalid or expired refresh token"));

        Long userId = stored.getUserId();
        User user = userRepository.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "User is disabled"));

        int revoked = refreshTokenRepository.revokeIfActive(stored.getId(), Instant.now());
        if (revoked == 0) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Refresh token already used");
        }
        return issuePair(user);
    }

    public void logout(String rawToken) {
        refreshTokenRepository.findByTokenHash(refreshTokenService.hash(rawToken))
                .ifPresent(t -> refreshTokenRepository.revokeIfActive(t.getId(), Instant.now()));
    }

    /**
     * Validates the old password, clears the must-change flag, revokes every active refresh token,
     * and returns a fresh token pair (whose access token no longer carries the gate).
     */
    @Transactional
    public TokenPair changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "Unauthorized"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Old password is incorrect");
        }
        if (newPassword.equals(oldPassword)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "New password must differ from old password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        refreshTokenRepository.revokeAllActiveByUserId(userId, Instant.now());
        return issuePair(user);
    }

    private TokenPair issuePair(User user) {
        String access = jwtService.issueAccessToken(user.getId(), user.getRole(), user.isMustChangePassword());
        String refresh = refreshTokenService.issue(user.getId());
        return new TokenPair(access, refresh);
    }
}
