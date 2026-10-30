package com.advisory.auth.api.dto;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;

/**
 * User projection for the admin listing. Never exposes the password hash.
 */
public record UserSummary(Long id, String username, UserRole role, boolean enabled, boolean mustChangePassword) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getRole(),
                user.isEnabled(), user.isMustChangePassword());
    }
}
