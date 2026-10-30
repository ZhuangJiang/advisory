package com.advisory.auth.api.dto;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;

public record MeResponse(Long id, String username, UserRole role, boolean mustChangePassword) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getUsername(), user.getRole(), user.isMustChangePassword());
    }
}
