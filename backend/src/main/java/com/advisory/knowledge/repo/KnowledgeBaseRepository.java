package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA repository for {@link KnowledgeBase}. Knowledge base listing is done through
 * {@code KnowledgeBaseService} with {@code JdbcTemplate} so the per-row {@code documentCount} can be
 * resolved in a single query (the {@code documents} table has no entity yet).
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
}
