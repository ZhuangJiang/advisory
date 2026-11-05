package com.advisory.common.error;

/**
 * 可预期的业务失败。
 *
 * <p>由 {@link ErrorCode} 同时决定响应信封里的 {@code code} 与 HTTP 状态码，因此抛出时只需选对枚举，
 * 不必在业务代码里关心状态行。与之相对，未预期的异常由全局处理器统一转成 500。</p>
 */
public class AppException extends RuntimeException {

    private final ErrorCode code;

    /**
     * @param code    业务错误码
     * @param message 面向用户的错误提示
     */
    public AppException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 省略提示时以枚举名兜底，便于日志定位到具体错误类型。
     *
     * @param code 业务错误码
     */
    public AppException(ErrorCode code) {
        this(code, code.name());
    }

    /**
     * @return 业务错误码，供全局异常处理器映射 HTTP 状态
     */
    public ErrorCode getCode() {
        return code;
    }
}
