package com.advisory.common.api;

import com.advisory.common.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 所有 {@code /api} 接口统一使用的响应信封。
 *
 * <p>{@code code} 为 {@code 0} 表示成功，其余为业务错误码；只有 {@code data} 在 {@code null} 时被省略，
 * {@code code} 与 {@code message} 始终参与序列化，以便前端在失败时也能拿到可展示的提示（即便提示为
 * {@code null}）。</p>
 *
 * @param code    业务错误码，{@code 0} 表示成功
 * @param message 提示信息，成功时固定为 {@code "ok"}
 * @param data    业务数据，失败时为 {@code null}
 * @param <T>     业务数据类型
 */
public record ApiResponse<T>(int code, String message,
                             @JsonInclude(JsonInclude.Include.NON_NULL) T data) {

    /** 成功响应固定使用的提示文案。 */
    private static final String SUCCESS_MESSAGE = "ok";

    /**
     * 构造带业务数据的成功响应。
     *
     * @param data 业务数据
     * @return {@code code = 0} 的响应信封
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, SUCCESS_MESSAGE, data);
    }

    /**
     * 构造不带数据的成功响应，用于删除、状态变更等没有返回体的写操作。
     *
     * @return {@code code = 0}、{@code data = null} 的响应信封
     */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, SUCCESS_MESSAGE, null);
    }

    /**
     * 按原始错误码构造失败响应。
     *
     * @param code    业务错误码
     * @param message 错误提示
     * @return 失败响应信封
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 按 {@link ErrorCode} 构造失败响应，错误码统一取自枚举，避免手写数字与枚举不一致。
     *
     * @param errorCode 错误码枚举
     * @param message   错误提示
     * @return 失败响应信封
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }
}
