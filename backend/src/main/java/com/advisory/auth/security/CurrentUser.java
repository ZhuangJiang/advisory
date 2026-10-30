package com.advisory.auth.security;

import com.advisory.auth.domain.UserRole;

/**
 * The authenticated caller, reconstructed from a JWT and stored as the security principal.
 */
public record CurrentUser(Long id, UserRole role) {
}
