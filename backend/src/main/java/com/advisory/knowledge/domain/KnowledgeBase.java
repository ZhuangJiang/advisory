package com.advisory.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A knowledge base (table {@code knowledge_bases}).
 *
 * <p>Column definitions must match {@code V1__init.sql} because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate}. {@code chunk_config} is a PostgreSQL
 * {@code jsonb} column mapped through Hibernate's JSON support; {@code embedding_model_id} is
 * intentionally nullable (models are created by a later task).</p>
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "embedding_model_id")
    private Long embeddingModelId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chunk_config", columnDefinition = "jsonb", nullable = false)
    private String chunkConfig = "{}";

    @Column(name = "reindex_status", nullable = false, length = 16)
    private String reindexStatus = "READY";

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KnowledgeBase() {
        // for JPA
    }

    public KnowledgeBase(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(Long embeddingModelId) {
        this.embeddingModelId = embeddingModelId;
    }

    public String getChunkConfig() {
        return chunkConfig;
    }

    public void setChunkConfig(String chunkConfig) {
        this.chunkConfig = chunkConfig;
    }

    public String getReindexStatus() {
        return reindexStatus;
    }

    public void setReindexStatus(String reindexStatus) {
        this.reindexStatus = reindexStatus;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
