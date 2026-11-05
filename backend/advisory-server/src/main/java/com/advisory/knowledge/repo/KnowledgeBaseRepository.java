package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link KnowledgeBase} 的 JPA 仓储。
 *
 * <p>知识库列表在 {@code KnowledgeBaseService} 里用 {@code JdbcTemplate} 查询，这样每行的
 * {@code documentCount} 能在一条 SQL 里一并算出（{@code documents} 表目前还没有对应实体）。</p>
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
}
