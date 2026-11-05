package com.advisory.common.error;

import org.springframework.http.HttpStatus;

/**
 * 响应信封 {@code code} 字段使用的业务错误码。
 *
 * <p>数字本身是与前端约定的契约，不可随意变更；{@link #getHttpStatus()} 只用于决定 HTTP 状态行，
 * 以便客户端与代理能按通用规则处理失败。新增错误码时按「HTTP 状态 × 100 + 序号」扩展。</p>
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

    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR),

    /**
     * 提示词超出模型上下文窗口。
     *
     * <p>单独一个码是为了让编排器能把「窗口不够」和「提供商故障」区分开：前者可以降级成
     * {@code TRUNCATED} 的答复，后者必须如实失败。提供商报错文本里的关键字由
     * {@code SpringAiChatModelGateway} 识别。</p>
     */
    MODEL_CONTEXT_EXCEEDED(50010, HttpStatus.INTERNAL_SERVER_ERROR);

    /** 与前端约定的业务错误码。 */
    private final int code;

    /** 该错误对应的 HTTP 状态。 */
    private final HttpStatus httpStatus;

    /**
     * @param code       业务错误码
     * @param httpStatus 对应的 HTTP 状态
     */
    ErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * @return 业务错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * @return 对应的 HTTP 状态
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
