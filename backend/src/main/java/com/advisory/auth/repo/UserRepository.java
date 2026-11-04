package com.advisory.auth.repo;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户数据访问入口。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 按登录名查找用户。
     *
     * @param username 登录名
     * @return 匹配的用户
     */
    Optional<User> findByUsername(String username);

    /**
     * 判断登录名是否已被占用，用于新建用户前的查重。
     *
     * @param username 登录名
     * @return 已存在返回 {@code true}
     */
    boolean existsByUsername(String username);

    /**
     * 统计某角色的用户数，启动引导用它判断是否已存在管理员。
     *
     * @param role 角色
     * @return 用户数量
     */
    long countByRole(UserRole role);
}
