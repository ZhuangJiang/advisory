package com.advisory.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 知识库树中的一个节点（表 {@code kb_nodes}）。
 *
 * <p>{@code path} 是 PostgreSQL 的 {@code ltree} 类型。这里按只读方式映射：路径由节点 id 与父节点路径推导，
 * 只通过 {@code KbNodeJdbcRepository} 中显式的 {@code CAST(... AS ltree)} 表达式写入。之所以仍然映射该列
 * （而不是忽略它），是为了让 {@code ddl-auto=validate} 能校验 {@code ltree} 列的类型。</p>
 */
@Entity
@Table(name = "kb_nodes")
public class KbNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "path", columnDefinition = "ltree", nullable = false, insertable = false, updatable = false)
    private String path;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KbNode() {
        // 供 JPA 使用
    }

    public KbNode(Long kbId, Long parentId, String name) {
        this.kbId = kbId;
        this.parentId = parentId;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getKbId() {
        return kbId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
