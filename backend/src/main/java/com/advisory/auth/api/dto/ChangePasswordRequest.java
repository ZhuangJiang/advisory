package com.advisory.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @NotBlank(message = "oldPassword must not be blank") String oldPassword,
        @NotBlank(message = "newPassword must not be blank") String newPassword) {
}
