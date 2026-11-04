package com.advisory.auth.repo;

import com.advisory.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * refresh token 的数据访问入口。
 *
 * <p>吊销操作一律写成「条件更新」而非「先查后改」，把并发安全交给单条 SQL 的原子性。</p>
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * 按摘要查找令牌；调用方需自行判断是否已吊销、是否过期。
     *
     * @param tokenHash 令牌摘要
     * @return 匹配的令牌
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 仅在令牌仍然有效时吊销它。
     *
     * <p>{@code and t.revokedAt is null} 让轮换具备原子性：同一令牌的并发刷新会竞争这一条更新语句，
     * 只有一个能得到 1，其余得到 0 并因此被判定为重放。</p>
     *
     * @param id        令牌主键
     * @param revokedAt 吊销时间
     * @return 1 表示本次成功吊销，0 表示此前已被吊销
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

    /**
     * 吊销某用户全部未过期的令牌，用于改密与禁用账号后强制下线。
     *
     * @param userId    用户主键
     * @param revokedAt 吊销时间
     * @return 实际被吊销的令牌数量
     */
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
