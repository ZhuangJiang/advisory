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
 * Reads the {@code Authorization: Bearer} header, validates the access token and populates the
 * security context. When the token carries {@code mustChangePassword = true}, only the password
 * self-service endpoints are allowed through; every other authenticated request is answered with
 * 403 {@code PASSWORD_CHANGE_REQUIRED}.
 *
 * <p>Not a {@code @Component}: it is declared as a {@code @Bean} in {@link SecurityConfig} so it
 * only runs inside the security filter chain, and so {@code @WebMvcTest} slices (which scan
 * {@code Filter} beans but not the JWT service) never try to instantiate it.</p>
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final Set<String> MUST_CHANGE_ALLOWED = Set.of(
            "/api/auth/me", "/api/auth/change-password", "/api/auth/logout");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

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
            // Invalid / expired / tampered token: leave the context anonymous and let the
            // authorization layer reject with 401 via the AuthenticationEntryPoint.
            log.debug("Rejected bearer token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
