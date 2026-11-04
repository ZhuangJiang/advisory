package com.advisory.chat.domain;

/**
 * Lifecycle of an assistant message ({@code messages.status}).
 *
 * <p>{@code PENDING} exists from the moment the SSE request is accepted (before the first model
 * call), {@code STREAMING} after the first delta, and the three terminal states are
 * {@code COMPLETED}, {@code FAILED} (provider error, never retried) and {@code TRUNCATED} (a partial
 * answer produced after a timeout or two consecutive unparsable structured answers). Only the last
 * three are ever returned as {@code done} on the stream.</p>
 */
public enum MessageStatus {
    PENDING,
    STREAMING,
    COMPLETED,
    FAILED,
    TRUNCATED
}
