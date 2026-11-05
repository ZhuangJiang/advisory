package com.advisory.provider.repo;

import com.advisory.provider.domain.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link ModelProvider} 的数据访问入口。
 *
 * <p>列表查询（含每行 {@code modelCount} 与掩码后的密钥）放在 {@code ProviderService} 里用
 * {@code JdbcTemplate} 一次查完，避免 N+1 查询与逐行解密。</p>
 */
public interface ModelProviderRepository extends JpaRepository<ModelProvider, Long> {
}
