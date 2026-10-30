package com.advisory.auth.repo;

import com.advisory.auth.domain.User;
import com.advisory.auth.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRole(UserRole role);
}
