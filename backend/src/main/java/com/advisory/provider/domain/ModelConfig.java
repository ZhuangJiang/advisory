package com.advisory.provider.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One locally registered model (table {@code models}) — the authoritative list an agent or a
 * knowledge base points at; the remote provider list is only ever an import source.
 *
 * <p>There is <em>no</em> unique index on {@code (provider_id, model_name)} in
 * {@code V1__init.sql}; duplicates are prevented by check-then-insert inside the service's
 * transaction. {@code params} is a {@code jsonb} column mapped as a raw JSON string, exactly like
 * {@code KnowledgeBase.chunkConfig}. {@code dimension} is only meaningful for
 * {@link ModelCapability#EMBEDDING} and must equal {@code app.embedding.dimension}.</p>
 */
@Entity
@Table(name = "models")
public class ModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 16)
    private ModelCapability capability;

    @Column(name = "dimension")
    private Integer dimension;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false)
    private String params = "{}";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModelConfig() {
        // for JPA
    }

    public ModelConfig(Long providerId, String modelName, ModelCapability capability, Integer dimension) {
        this.providerId = providerId;
        this.modelName = modelName;
        this.capability = capability;
        this.dimension = dimension;
    }

    public Long getId() {
        return id;
    }

    public Long getProviderId() {
        return providerId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public ModelCapability getCapability() {
        return capability;
    }

    public void setCapability(ModelCapability capability) {
        this.capability = capability;
    }

    public Integer getDimension() {
        return dimension;
    }

    public void setDimension(Integer dimension) {
        this.dimension = dimension;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
