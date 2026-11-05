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
 * 登录、刷新、登出与改密的编排。
 *
 * <p>统一在这里处理「账号是否存在/启用」与「令牌是否仍有效」的判断，控制器只做参数接收。
 * 除 {@code login} 与 {@code logout} 外都要求事务，因为令牌轮换必须先吊销旧行再插入新行。</p>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    /**
     * @param userRepository        用户仓储
     * @param refreshTokenRepository refresh token 仓储
     * @param refreshTokenService   令牌签发与摘要
     * @param jwtService            access token 签发与解析
     * @param passwordEncoder       密码校验
     */
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

    /**
     * 校验账号密码并签发令牌对。
     *
     * <p>「账号不存在」「已禁用」「密码错误」共用同一个 401 文案，避免被用来探测有效用户名。</p>
     *
     * @param username 登录名
     * @param password 明文密码
     * @return 令牌对
     */
    public TokenPair login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .filter(User::isEnabled)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        return issuePair(user);
    }

    /**
     * 轮换 refresh token：把出示的令牌标记为已吊销，并签发一对新令牌。
     *
     * <p>已轮换过的令牌再次出现按重放处理，返回 401；这依赖 {@code revokeIfActive} 的条件更新
     * 在并发下只有一个调用能拿到 1。</p>
     *
     * @param rawToken 客户端提交的 refresh token 原文
     * @return 新的令牌对
     */
    @Transactional
    public TokenPair refresh(String rawToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(refreshTokenService.hash(rawToken))
                .filter(t -> t.getRevokedAt() == null)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "refresh token 无效或已过期"));

        Long userId = stored.getUserId();
        User user = userRepository.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "用户已被禁用"));

        int revoked = refreshTokenRepository.revokeIfActive(stored.getId(), Instant.now());
        if (revoked == 0) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "refresh token 已被使用");
        }
        return issuePair(user);
    }

    /**
     * 登出：吊销传入的 refresh token。
     *
     * <p>令牌不存在时静默返回，既不报错也不泄露令牌是否有效，便于客户端重复调用。</p>
     *
     * @param rawToken 客户端提交的 refresh token 原文
     */
    public void logout(String rawToken) {
        refreshTokenRepository.findByTokenHash(refreshTokenService.hash(rawToken))
                .ifPresent(t -> refreshTokenRepository.revokeIfActive(t.getId(), Instant.now()));
    }

    /**
     * 自助改密：校验旧密码、清除强制改密标记、吊销全部存量 refresh token，并下发新的令牌对。
     *
     * <p>新令牌不再携带改密标记，因此调用方拿到后即可正常访问其他接口；吊销存量令牌则让其他设备上的
     * 会话无法继续续期。</p>
     *
     * @param userId      用户主键
     * @param oldPassword 旧密码
     * @param newPassword 新密码，不得与旧密码相同
     * @return 新的令牌对
     */
    @Transactional
    public TokenPair changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "原密码不正确");
        }
        if (newPassword.equals(oldPassword)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "新密码不能与原密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        refreshTokenRepository.revokeAllActiveByUserId(userId, Instant.now());
        return issuePair(user);
    }

    /**
     * 同时签发 access token 与 refresh token。
     *
     * @param user 目标用户
     * @return 令牌对
     */
    private TokenPair issuePair(User user) {
        String access = jwtService.issueAccessToken(user.getId(), user.getRole(), user.isMustChangePassword());
        String refresh = refreshTokenService.issue(user.getId());
        return new TokenPair(access, refresh);
    }
}
