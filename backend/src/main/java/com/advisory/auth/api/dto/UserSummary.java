package com.advisory.auth.api.dto;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;

/**
 * 用户管理列表使用的投影。
 *
 * <p>刻意只暴露必要字段，密码哈希等敏感信息永不出现在接口返回中。</p>
 *
 * @param id                 用户主键
 * @param username           登录名
 * @param role               平台角色
 * @param enabled            是否启用
 * @param mustChangePassword 是否仍需强制修改密码
 */
public record UserSummary(Long id, String username, UserRole role, boolean enabled, boolean mustChangePassword) {

    /**
     * 由实体转换。
     *
     * @param user 用户实体
     * @return 用户摘要
     */
    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getRole(),
                user.isEnabled(), user.isMustChangePassword());
    }
}
