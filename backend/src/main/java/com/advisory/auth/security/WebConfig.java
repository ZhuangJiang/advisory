package com.advisory.auth.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 注册 {@link CurrentUserArgumentResolver}，使控制器方法可以直接声明 {@link CurrentUser} 参数。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    /**
     * @param currentUserArgumentResolver 当前用户参数解析器
     */
    public WebConfig(CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    /**
     * 追加自定义解析器；MVC 内置的解析器不受影响。
     *
     * @param resolvers 解析器列表
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
