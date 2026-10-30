package com.advisory.common.error;

import org.springframework.http.HttpStatus;

/**
 * Business error codes returned in the response envelope's {@code code} field.
 *
 * <p>The numeric code is the contract with the frontend; {@link #getHttpStatus()} only drives
 * the HTTP status line so clients and proxies can reason about failures generically.</p>
 */
public enum ErrorCode {

    VALIDATION_FAILED(40000, HttpStatus.BAD_REQUEST),
    EMBEDDING_DIMENSION_MISMATCH(40010, HttpStatus.BAD_REQUEST),

    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED),

    FORBIDDEN(40300, HttpStatus.FORBIDDEN),
    PASSWORD_CHANGE_REQUIRED(40301, HttpStatus.FORBIDDEN),

    RESOURCE_NOT_FOUND(40400, HttpStatus.NOT_FOUND),

    CONFLICT(40900, HttpStatus.CONFLICT),
    KB_NODE_NAME_DUPLICATED(40901, HttpStatus.CONFLICT),
    NODE_NOT_EMPTY(40902, HttpStatus.CONFLICT),
    KB_IN_USE(40903, HttpStatus.CONFLICT),
    MODEL_IN_USE(40904, HttpStatus.CONFLICT),
    KB_REINDEXING(40905, HttpStatus.CONFLICT),
    CONVERSATION_BUSY(40906, HttpStatus.CONFLICT),

    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final HttpStatus httpStatus;

    ErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
