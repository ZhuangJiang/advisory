package com.advisory.provider.repo;

import com.advisory.provider.domain.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA repository for {@link ModelProvider}. Listing (with per-row {@code modelCount} and the masked
 * key) is done in {@code ProviderService} with {@code JdbcTemplate} in a single query.
 */
public interface ModelProviderRepository extends JpaRepository<ModelProvider, Long> {
}
