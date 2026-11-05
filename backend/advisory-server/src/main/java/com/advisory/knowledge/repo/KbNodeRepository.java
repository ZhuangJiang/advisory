package com.advisory.knowledge.repo;

import com.advisory.knowledge.domain.KbNode;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link KbNode} 的 JPA 仓储，只用于按实体删除。
 *
 * <p>所有读取与路径写入都走 {@link KbNodeJdbcRepository}：写 {@code path} 需要显式 {@code ltree} 转换，
 * 而 JDBC 写完再用 JPA 读会拿到持久化上下文里缓存的旧实体。节点树的唯一读取路径就是 JDBC 仓储返回的
 * {@code KbNodeRow} 投影。</p>
 */
public interface KbNodeRepository extends JpaRepository<KbNode, Long> {
}
