package com.advisory.auth.security;

import com.advisory.auth.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 无状态 JWT 安全配置：关闭 CSRF、不建会话，除认证入口与健康检查外的请求都必须已认证；
 * 同时开启方法级安全以支持 {@code @PreAuthorize}。过滤器链内的 401/403 由专用的入口点与拒绝处理器
 * 写成统一的 API 信封。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 密码哈希器，全平台统一使用 BCrypt。
     *
     * @return BCrypt 编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 声明 JWT 过滤器 bean，供安全链引用。
     *
     * @param jwtService   令牌解析服务
     * @param objectMapper 用于写出错误信封
     * @return 过滤器实例
     */
    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        return new JwtAuthFilter(jwtService, objectMapper);
    }

    /**
     * 关闭 Servlet 容器的自动注册，让 JWT 过滤器只在安全链里执行一次。
     *
     * <p>Spring Boot 会把容器中所有 {@code Filter} bean 自动挂到根过滤链上，不显式禁用就会与
     * 安全链各执行一次，造成重复解析令牌。</p>
     *
     * @param jwtAuthFilter 过滤器
     * @return 已禁用的注册对象
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter jwtAuthFilter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(jwtAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 安全过滤链：无状态、禁用 CSRF，认证入口与健康检查放行，其余请求需认证。
     *
     * @param http          安全构建器
     * @param jwtAuthFilter JWT 过滤器
     * @param objectMapper  用于写出错误信封
     * @return 构建好的过滤链
     * @throws Exception 构建失败时抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SseEmitter 结束时走 ASYNC 派发，此时 JWT 过滤器不会再执行；若在这里重新鉴权，
                        // 被判为匿名后连错误都写不出去（响应已提交），客户端只会看到连接被 500 掐断。
                        // ERROR 派发放行的原因相同：错误页不应再需要一次鉴权。两者都是「请求到达时已鉴权」
                        // 的内部延续，放行不会放宽外部访问。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                                "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
