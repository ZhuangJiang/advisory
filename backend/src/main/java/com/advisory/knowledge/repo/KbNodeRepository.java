package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.KbNode;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA repository for {@link KbNode}, used only for entity deletes.
 *
 * <p>All reads and all path writes go through {@link KbNodeJdbcRepository}: {@code path} needs
 * explicit {@code ltree} casts to write, and a JPA read after a JDBC write would hand back the stale
 * entity cached in the persistence context. The JDBC repository's {@code KbNodeRow} projections are
 * the single read path for the node tree.</p>
 */
public interface KbNodeRepository extends JpaRepository<KbNode, Long> {
}
