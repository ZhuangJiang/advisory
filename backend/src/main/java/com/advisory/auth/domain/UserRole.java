package com.advisory.auth.domain;

/**
 * Platform-wide roles. Stored as the string name in {@code users.role} (CHECK-constrained).
 *
 * <p>Spring Security authorities are derived by prefixing {@code ROLE_} so
 * {@code hasRole('ADMIN')} keeps working.</p>
 */
public enum UserRole {
    ADMIN,
    USER
}
