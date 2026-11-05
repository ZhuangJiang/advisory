package com.advisory.auth.security;

import com.advisory.common.api.ApiResponse;
import com.advisory.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 为匿名请求被过滤器链拒绝时写出 {@code {code, message, data}} 信封。
 *
 * <p>Spring Security 在 {@code @RestControllerAdvice} 之前处理这类拒绝，没有这个入口点，
 * 401 响应体就是空的，前端拿不到可展示的提示。</p>
 *
 * <p>日志同理：这里不记，控制台就完全看不到鉴权失败，排障时只能靠猜。因此按 WARN 记一条
 * 「方法 + 路径 + 原因」——注意只能记路径，不能记 {@code Authorization} 头。</p>
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(JsonAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper 用于序列化响应信封
     */
    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 输出 401 与统一信封，并记录一条 WARN 级别的鉴权失败日志。
     *
     * @param request       当前请求
     * @param response      响应
     * @param authException 触发拒绝的认证异常
     * @throws IOException 写响应失败时抛出
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.warn("未认证访问被拒 {} {}: {}", request.getMethod(), request.getRequestURI(),
                authException.getMessage());
        response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(ErrorCode.UNAUTHORIZED, "Unauthorized"));
    }
}
