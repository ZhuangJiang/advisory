package com.advisory.auth.security;

import com.advisory.common.api.ApiResponse;
import com.advisory.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 为「已认证但无权限」的请求写出 {@code {code, message, data}} 信封。
 *
 * <p>这类拒绝发生在过滤器链内部，早于 {@code @RestControllerAdvice}，不放这里处理的话响应体是空的，
 * 前端无法按统一格式解析错误。</p>
 *
 * <p>日志同理：全局异常处理器在这里不生效，所以 WARN 必须就地打一条「方法 + 路径 + 原因」，
 * 否则 403 在控制台上毫无痕迹。</p>
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper 用于序列化响应信封
     */
    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 输出 403 与统一信封，并记录一条 WARN 级别的拒绝日志。
     *
     * @param request               当前请求
     * @param response              响应
     * @param accessDeniedException 触发拒绝的权限异常
     * @throws IOException 写响应失败时抛出
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("权限不足 {} {}: {}", request.getMethod(), request.getRequestURI(),
                accessDeniedException.getMessage());
        response.setStatus(ErrorCode.FORBIDDEN.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(ErrorCode.FORBIDDEN, "没有访问权限"));
    }
}
