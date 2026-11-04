package com.advisory.provider.domain;

/**
 * What a {@code models} row is used for. Stored as a {@code VARCHAR(16)} with a check constraint in
 * {@code V1__init.sql}.
 */
public enum ModelCapability {
    CHAT,
    EMBEDDING
}
