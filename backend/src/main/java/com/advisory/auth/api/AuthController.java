package com.advisory.auth.api;

import com.advisory.auth.api.dto.ChangePasswordRequest;
import com.advisory.auth.api.dto.LoginRequest;
import com.advisory.auth.api.dto.MeResponse;
import com.advisory.auth.api.dto.RefreshRequest;
import com.advisory.auth.api.dto.TokenPair;
import com.advisory.auth.domain.User;
import com.advisory.auth.repo.UserRepository;
import com.advisory.auth.security.CurrentUser;
import com.advisory.auth.service.AuthService;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证入口：登录、刷新、登出、当前用户与自助改密。
 *
 * <p>{@code /login}、{@code /refresh}、{@code /logout} 在安全链中被显式放行，其余接口需要携带
 * 有效 access token；带 {@code mustChangePassword} 标记的令牌只能访问 {@code /me}、
 * {@code /change-password} 与 {@code /logout}。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    /**
     * 账号密码登录。
     *
     * <p>用户不存在、被禁用、密码错误统一返回同一个 401 文案，避免通过错误差异枚举出有效用户名。</p>
     *
     * @param body 登录请求
     * @return access token 与 refresh token
     */
    @PostMapping("/login")
    public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest body) {
        return ApiResponse.ok(authService.login(body.username(), body.password()));
    }

    /**
     * 用 refresh token 换取新的令牌对（旧令牌随即作废）。
     *
     * @param body 携带 refresh token 的请求
     * @return 新的令牌对
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest body) {
        return ApiResponse.ok(authService.refresh(body.refreshToken()));
    }

    /**
     * 登出：吊销传入的 refresh token。access token 无法即时失效，依靠其较短的有效期收敛。
     *
     * @param body 携带 refresh token 的请求
     * @return 空响应
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest body) {
        authService.logout(body.refreshToken());
        return ApiResponse.ok();
    }

    /**
     * 返回当前登录用户，供前端刷新页面时恢复会话。
     *
     * @param currentUser 由 JWT 解析出的调用者
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(CurrentUser currentUser) {
        User user = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "Unauthorized"));
        return ApiResponse.ok(MeResponse.from(user));
    }

    /**
     * 自助修改密码；成功后旧 refresh token 全部失效，并下发不再带强制改密标记的新令牌。
     *
     * @param currentUser 由 JWT 解析出的调用者
     * @param body        旧密码与新密码
     * @return 新的令牌对
     */
    @PostMapping("/change-password")
    public ApiResponse<TokenPair> changePassword(CurrentUser currentUser,
                                                 @Valid @RequestBody ChangePasswordRequest body) {
        return ApiResponse.ok(authService.changePassword(currentUser.id(), body.oldPassword(), body.newPassword()));
    }
}
