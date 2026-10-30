package com.advisory.auth.api.dto;

import com.advisory.auth.domain.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateUserRequest(
        @NotBlank(message = "username must not be blank") String username,
        @NotBlank(message = "password must not be blank") String password,
        @NotNull(message = "role must not be null") UserRole role) {
}
