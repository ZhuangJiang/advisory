package com.advisory.auth.security;

/**
 * A validated JWT: the authenticated {@link CurrentUser} plus the {@code mustChangePassword} flag
 * the filter needs in order to enforce the password-change gate before routing.
 */
public record ParsedToken(CurrentUser user, boolean mustChangePassword) {
}
