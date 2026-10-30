package com.advisory.auth.repo;

import com.advisory.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes the given token only when it is still active. The conditional update makes rotation
     * atomic: concurrent refreshes with the same token race on this statement and exactly one wins.
     *
     * @return 1 when the token was active and is now revoked, 0 when it was already revoked
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :revokedAt
             where t.id = :id
               and t.revokedAt is null
            """)
    int revokeIfActive(@Param("id") Long id, @Param("revokedAt") Instant revokedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :revokedAt
             where t.userId = :userId
               and t.revokedAt is null
            """)
    int revokeAllActiveByUserId(@Param("userId") Long userId, @Param("revokedAt") Instant revokedAt);
}
