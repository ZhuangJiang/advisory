package com.advisory.auth.service;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import com.advisory.auth.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建一个管理员账号，使平台开箱可用。
 *
 * <p>仅当库中不存在任何 {@code ADMIN} 时才创建，已有管理员绝不会被覆盖——否则升级重启会把线上密码
 * 重置回配置值。新账号带 {@code must_change_password = true}，首次登录必须先改密。</p>
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    /**
     * @param userRepository  用户仓储
     * @param passwordEncoder 密码加密
     * @param username        引导账号的用户名，来自配置
     * @param password        引导账号的初始密码，来自配置
     */
    public BootstrapAdminInitializer(UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     @Value("${app.bootstrap-admin.username}") String username,
                                     @Value("${app.bootstrap-admin.password}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    /**
     * 应用启动后执行：无管理员则建一个，并提醒使用默认密码的风险。
     *
     * @param args 启动参数，未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.countByRole(UserRole.ADMIN) > 0) {
            return;
        }

        User admin = new User(username, passwordEncoder.encode(password), UserRole.ADMIN);
        admin.setEnabled(true);
        admin.setMustChangePassword(true);
        userRepository.save(admin);

        log.info("Created bootstrap admin '{}'", username);
        if ("admin".equals(password)) {
            log.warn("Bootstrap admin '{}' is using the default password — change it immediately", username);
        }
    }
}
