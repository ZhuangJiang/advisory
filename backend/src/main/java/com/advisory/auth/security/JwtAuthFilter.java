package com.advisory.auth.security;

import com.advisory.auth.service.JwtService;
import com.advisory.common.api.ApiResponse;
import com.advisory.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 解析 {@code Authorization: Bearer} 头、校验 access token 并填充安全上下文。
 *
 * <p>若令牌带有 {@code mustChangePassword = true}，只放行改密相关的自助接口，其余已认证请求一律
 * 返回 403 {@code PASSWORD_CHANGE_REQUIRED}——这条闸门放在过滤器里而不是各控制器里，避免遗漏。</p>
 *
 * <p>刻意不加 {@code @Component}：改由 {@link SecurityConfig} 以 {@code @Bean} 声明，既保证它只在
 * 安全过滤链内执行，也避免 {@code @WebMvcTest} 切片（会扫描 {@code Filter} 类型的 bean，但不加载
 * JWT 服务）在启动时因缺少依赖而失败。</p>
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /** 强制改密状态下仍可访问的接口：查身份、改密、登出。 */
    private static final Set<String> MUST_CHANGE_ALLOWED = Set.of(
            "/api/auth/me", "/api/auth/change-password", "/api/auth/logout");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    /**
     * @param jwtService   令牌解析服务
     * @param objectMapper 用于写出错误信封
     */
    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    /**
     * 无 Bearer 头时直接放行，由后续的授权规则决定是否 401；令牌非法时清空上下文同样放行，
     * 让 {@code AuthenticationEntryPoint} 统一产出 401，避免在过滤器里各写一套错误格式。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring("Bearer ".length());
        try {
            ParsedToken parsed = jwtService.parse(token);
            if (parsed.mustChangePassword() && !MUST_CHANGE_ALLOWED.contains(request.getRequestURI())) {
                writeError(response, ErrorCode.PASSWORD_CHANGE_REQUIRED, "Password change required");
                return;
            }

            CurrentUser currentUser = parsed.user();
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    currentUser, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.role().name())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (RuntimeException e) {
            // 令牌非法、过期或被篡改：保持匿名上下文，交由授权层通过 AuthenticationEntryPoint 统一返回 401。
            log.debug("Rejected bearer token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 直接在过滤器内写出错误信封（此时尚未进入 MVC，无法借助全局异常处理器）。
     *
     * @param response 响应
     * @param code     业务错误码
     * @param message  错误提示
     */
    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
