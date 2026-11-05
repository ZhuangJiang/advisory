package com.advisory.common.error;

import com.advisory.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 把各类异常统一翻译成 {@link ApiResponse} 信封。
 *
 * <p>所有分支都会留下日志，否则「客户端收到 401、控制台一片安静」会让排障寸步难行。级别按后果划分：
 * 可预期的客户端错误（4xx）记 WARN，只打「方法 + 路径 + 错误码 + 提示」，绝不打印请求体，
 * 避免密码等敏感字段进日志；未预期异常记 ERROR 并带堆栈，但不把异常信息回显给客户端，
 * 防止泄露内部实现细节。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 500 响应对外的固定文案，任何内部异常都不向客户端透传细节。 */
    private static final String INTERNAL_ERROR_MESSAGE = "Internal server error";

    /**
     * 业务异常：错误码与 HTTP 状态均取自 {@link ErrorCode}。
     *
     * <p>5xx 属于服务端问题，按 ERROR 带堆栈记录；4xx 是调用方用法问题，记 WARN 即可。</p>
     *
     * @param ex      业务异常
     * @param request 当前请求，用于日志定位
     * @return 失败响应
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex, HttpServletRequest request) {
        ErrorCode code = ex.getCode();
        if (code.getHttpStatus().is5xxServerError()) {
            log.error("业务异常 {} -> {}: {}", describe(request), code, ex.getMessage(), ex);
        } else {
            log.warn("业务异常 {} -> {}: {}", describe(request), code, ex.getMessage());
        }
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(code, ex.getMessage()));
    }

    /**
     * {@code @Valid} 校验失败：把各字段的错误拼成一条可读提示。
     *
     * @param ex      参数校验异常
     * @param request 当前请求，用于日志定位
     * @return 400 响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                             HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Validation failed";
        }
        log.warn("参数校验失败 {}: {}", describe(request), message);
        return validationError(message);
    }

    /**
     * 方法级参数校验失败（如 {@code @Min} 注解在查询参数上）。
     *
     * @param ex      方法参数校验异常
     * @param request 当前请求，用于日志定位
     * @return 400 响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex,
                                                                          HttpServletRequest request) {
        log.warn("方法参数校验失败 {}", describe(request));
        return validationError("Validation failed");
    }

    /**
     * 路径变量或查询参数类型不匹配（例如把非数字传给了 {@code Long}）。
     *
     * @param ex      类型转换异常
     * @param request 当前请求，用于日志定位
     * @return 400 响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                               HttpServletRequest request) {
        log.warn("请求参数类型不匹配 {}: {}={}", describe(request), ex.getName(), ex.getValue());
        return validationError("Invalid request parameter");
    }

    /**
     * 请求体无法解析（JSON 语法错误、枚举取值非法等）。
     *
     * @param ex      报文解析异常
     * @param request 当前请求，用于日志定位
     * @return 400 响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                 HttpServletRequest request) {
        log.warn("请求体无法解析 {} ({})", describe(request), ex.getClass().getSimpleName());
        return validationError("Malformed request body");
    }

    /**
     * 静态资源或未映射的路径。
     *
     * @param ex      资源未找到异常
     * @param request 当前请求，用于日志定位
     * @return 404 响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex,
                                                             HttpServletRequest request) {
        log.warn("路径不存在 {}: {}", describe(request), ex.getResourcePath());
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found"));
    }

    /**
     * HTTP 方法不被支持。
     *
     * @param ex      方法不支持异常
     * @param request 当前请求，用于日志定位
     * @return 405 响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                   HttpServletRequest request) {
        log.warn("HTTP 方法不被支持 {}: {}", describe(request), ex.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, "Method not allowed"));
    }

    /**
     * 已通过认证但权限不足。
     *
     * <p>WARN 级别记录，因为「有身份却没权限」往往是权限配置或前端入口暴露错了，
     * 比匿名 401 更值得关注。</p>
     *
     * @param ex      访问被拒异常
     * @param request 当前请求，用于日志定位
     * @return 403 响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex,
                                                               HttpServletRequest request) {
        log.warn("权限不足 {}: {}", describe(request), ex.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.FORBIDDEN, "Forbidden"));
    }

    /**
     * 认证失败，主要来自方法级安全校验。
     *
     * @param ex      认证异常
     * @param request 当前请求，用于日志定位
     * @return 401 响应
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex,
                                                                 HttpServletRequest request) {
        log.warn("认证失败 {}: {}", describe(request), ex.getMessage());
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "Unauthorized"));
    }

    /**
     * 兜底处理：记录堆栈并返回不泄露细节的 500。
     *
     * @param ex      未预期异常
     * @param request 当前请求，用于日志定位
     * @return 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未处理异常 {}", describe(request), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
    }

    /**
     * 把请求压成一行日志前缀，便于在控制台里 grep 同一次调用。
     *
     * <p>只取方法与路径：查询串里可能出现敏感参数，请求体更不能整段打印。</p>
     *
     * @param request 当前请求
     * @return 形如 {@code POST /api/auth/login} 的描述
     */
    private static String describe(HttpServletRequest request) {
        return request.getMethod() + ' ' + request.getRequestURI();
    }

    /**
     * 构造 400 响应的公共出口。
     *
     * @param message 错误提示
     * @return 400 响应
     */
    private ResponseEntity<ApiResponse<Void>> validationError(String message) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, message));
    }
}
