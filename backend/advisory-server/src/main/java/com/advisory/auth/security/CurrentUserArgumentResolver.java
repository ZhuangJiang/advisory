package com.advisory.auth.security;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把安全上下文中的 {@link CurrentUser}（由 {@link JwtAuthFilter} 写入）注入控制器方法参数。
 *
 * <p>控制器因此不必直接接触 {@code SecurityContextHolder}，也不必在签名里声明 {@code Authentication}。</p>
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * 只处理声明为 {@link CurrentUser} 的参数。
     *
     * @param parameter 方法参数
     * @return 参数类型为 {@link CurrentUser} 时返回 {@code true}
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    /**
     * 从安全上下文取出主体。理论上走到这里一定已通过鉴权，取不到仍按 401 处理，避免空指针外泄。
     *
     * @return 当前调用者
     */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser;
        }
        throw new AppException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
    }
}
