package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@link Document} 的 JPA 仓储，只用于插入以及按 {@code object_key} 做幂等重放查询。
 *
 * <p>需要观察到别处比较并设置写入结果的读取（状态、列表、软删除）改为在 {@code DocumentService} 中用
 * {@code JdbcTemplate} 完成：JPA 的 {@code findById} 可能从持久化上下文里取出旧实体。</p>
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByObjectKey(String objectKey);
}
