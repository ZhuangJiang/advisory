package com.advisory.common.api;

import com.advisory.common.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform response envelope for every {@code /api} endpoint.
 *
 * <p>{@code code} is {@code 0} on success and the business error code otherwise. Only {@code data}
 * is omitted when {@code null}; {@code code} and {@code message} always serialize so clients can
 * surface the message (even when it is {@code null}).</p>
 */
public record ApiResponse<T>(int code, String message,
                             @JsonInclude(JsonInclude.Include.NON_NULL) T data) {

    private static final String SUCCESS_MESSAGE = "ok";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, SUCCESS_MESSAGE, data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, SUCCESS_MESSAGE, null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }
}
