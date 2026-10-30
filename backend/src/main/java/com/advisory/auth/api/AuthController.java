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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest body) {
        return ApiResponse.ok(authService.login(body.username(), body.password()));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest body) {
        return ApiResponse.ok(authService.refresh(body.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest body) {
        authService.logout(body.refreshToken());
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(CurrentUser currentUser) {
        User user = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "Unauthorized"));
        return ApiResponse.ok(MeResponse.from(user));
    }

    @PostMapping("/change-password")
    public ApiResponse<TokenPair> changePassword(CurrentUser currentUser,
                                                 @Valid @RequestBody ChangePasswordRequest body) {
        return ApiResponse.ok(authService.changePassword(currentUser.id(), body.oldPassword(), body.newPassword()));
    }
}
