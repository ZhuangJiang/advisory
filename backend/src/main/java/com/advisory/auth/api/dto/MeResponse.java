package com.advisory.auth.api.dto;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;

/**
 * 当前登录用户的概要信息。
 *
 * <p>{@code mustChangePassword} 直接透传给前端，用于把用户强制引导到改密页。</p>
 *
 * @param id                 用户主键
 * @param username           登录名
 * @param role               平台角色
 * @param mustChangePassword 是否仍需强制修改密码
 */
public record MeResponse(Long id, String username, UserRole role, boolean mustChangePassword) {

    /**
     * 由实体转换，避免把密码哈希带出接口层。
     *
     * @param user 用户实体
     * @return 概要信息
     */
    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getUsername(), user.getRole(), user.isMustChangePassword());
    }
}
