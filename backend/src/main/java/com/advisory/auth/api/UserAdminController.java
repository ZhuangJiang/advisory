package com.advisory.auth.api;

import com.advisory.auth.api.dto.CreateUserRequest;
import com.advisory.auth.api.dto.PatchUserRequest;
import com.advisory.auth.api.dto.UserSummary;
import com.advisory.auth.domain.User;
import com.advisory.auth.repo.RefreshTokenRepository;
import com.advisory.auth.repo.UserRepository;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.api.PageResponse;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 用户管理接口，仅管理员可用。
 *
 * <p>新建与重置密码都会把 {@code mustChangePassword} 置为 {@code true}，强制对方首次登录后自行改密；
 * 重置密码或禁用用户会立刻吊销该用户所有未过期的 refresh token，使既有会话无法续期。</p>
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    /** 列表接口允许的最大页长，防止一次拉走全表。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAdminController(UserRepository userRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 分页查询用户列表。
     *
     * @param page 页码，从 0 开始，负数按 0 处理
     * @param size 每页条数，越界时收敛到 1~{@value #MAX_PAGE_SIZE}
     * @return 用户分页数据
     */
    @GetMapping
    public ApiResponse<PageResponse<UserSummary>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pageIndex, pageSize);
        Page<UserSummary> result = userRepository.findAll(pageable).map(UserSummary::from);
        return ApiResponse.ok(PageResponse.of(result));
    }

    /**
     * 新建用户。
     *
     * @param body 用户名、初始密码与角色
     * @return 新建的用户摘要
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserSummary> create(@Valid @RequestBody CreateUserRequest body) {
        if (userRepository.existsByUsername(body.username())) {
            throw new AppException(ErrorCode.CONFLICT, "Username already exists");
        }
        User user = new User(body.username(), passwordEncoder.encode(body.password()), body.role());
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user = userRepository.save(user);
        return ApiResponse.ok(UserSummary.from(user));
    }

    /**
     * 局部更新用户：启用/禁用与重置密码均为可选，{@code null} 表示保持原值。
     *
     * @param id   用户主键
     * @param body 待更新字段
     * @return 更新后的用户摘要
     */
    @PatchMapping("/{id}")
    public ApiResponse<UserSummary> patch(@PathVariable Long id, @RequestBody PatchUserRequest body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));

        boolean resetPassword = body.password() != null;
        if (resetPassword) {
            if (body.password().isBlank()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "Password must not be blank");
            }
            user.setPasswordHash(passwordEncoder.encode(body.password()));
            user.setMustChangePassword(true);
        }

        boolean disable = body.enabled() != null && !body.enabled();
        if (body.enabled() != null) {
            user.setEnabled(body.enabled());
        }

        user = userRepository.save(user);

        // 重置密码或禁用用户后，该用户所有未过期的 refresh token 立即失效，已有会话无法再续期。
        if (resetPassword || disable) {
            refreshTokenRepository.revokeAllActiveByUserId(user.getId(), Instant.now());
        }
        return ApiResponse.ok(UserSummary.from(user));
    }
}
