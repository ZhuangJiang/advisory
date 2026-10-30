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
 * A node in a knowledge base's tree (table {@code kb_nodes}).
 *
 * <p>The {@code path} column is a PostgreSQL {@code ltree}. It is mapped read-only here: the path is
 * derived from the node id and its parent's path, so it is written exclusively through
 * {@code KbNodeJdbcRepository} with explicit {@code CAST(... AS ltree)} expressions. Mapping it
 * (rather than omitting it) lets {@code ddl-auto=validate} check the {@code ltree} column type.</p>
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
        // for JPA
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
