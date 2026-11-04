package com.advisory.provider.repo;

import com.advisory.provider.domain.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * JPA repository for {@link ModelConfig}. No derived {@code existsByProviderIdAndModelName}: with
 * no unique index on {@code (provider_id, model_name)} the duplicate guard lives in the service
 * transaction (check-then-insert), so reads of the pair go through
 * {@link #findByProviderIdAndModelName} inside that same transaction.
 */
public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {

    List<ModelConfig> findByProviderIdOrderByIdAsc(Long providerId);

    List<ModelConfig> findByProviderIdAndModelName(Long providerId, String modelName);

    long countByProviderId(Long providerId);
}
