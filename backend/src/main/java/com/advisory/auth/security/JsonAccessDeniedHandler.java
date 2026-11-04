package com.advisory.auth.security;

import com.advisory.common.api.ApiResponse;
import com.advisory.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 为「已认证但无权限」的请求写出 {@code {code, message, data}} 信封。
 *
 * <p>这类拒绝发生在过滤器链内部，早于 {@code @RestControllerAdvice}，不放这里处理的话响应体是空的，
 * 前端无法按统一格式解析错误。</p>
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper 用于序列化响应信封
     */
    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 输出 403 与统一信封。
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(ErrorCode.FORBIDDEN, "Forbidden"));
    }
}
