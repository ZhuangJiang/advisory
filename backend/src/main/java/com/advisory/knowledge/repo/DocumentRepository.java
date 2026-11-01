package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * JPA repository for {@link Document}, used for inserts and for the idempotent replay lookup on
 * {@code object_key}. Reads that must observe an out-of-band compare-and-set write (status, listing,
 * soft delete) go through {@code JdbcTemplate} in {@code DocumentService} instead, because a JPA
 * {@code findById} could return the stale entity from the persistence context.
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByObjectKey(String objectKey);
}
