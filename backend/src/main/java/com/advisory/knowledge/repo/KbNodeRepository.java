package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.KbNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * JPA repository for {@link KbNode}. {@code path} is a read-only mapping; path-mutating operations
 * (insert, subtree move) live in {@link KbNodeJdbcRepository} because they require explicit
 * {@code ltree} casts and two-step inserts.
 */
public interface KbNodeRepository extends JpaRepository<KbNode, Long> {

    List<KbNode> findByKbIdOrderByPath(Long kbId);

    long countByParentId(Long parentId);
}
