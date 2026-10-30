package com.advisory.common.error;

/**
 * Expected business failure. The {@link ErrorCode} decides both the envelope code and the HTTP status.
 */
public class AppException extends RuntimeException {

    private final ErrorCode code;

    public AppException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AppException(ErrorCode code) {
        this(code, code.name());
    }

    public ErrorCode getCode() {
        return code;
    }
}
