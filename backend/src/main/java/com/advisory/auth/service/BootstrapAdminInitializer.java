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
 * Seeds a single admin account on first boot (when no ADMIN exists) so the platform is usable out
 * of the box. The account is created with {@code must_change_password = true}; an existing admin is
 * never overwritten.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public BootstrapAdminInitializer(UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     @Value("${app.bootstrap-admin.username}") String username,
                                     @Value("${app.bootstrap-admin.password}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

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
