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
 * 本地登记的模型（表 {@code models}），也是 Agent 与知识库真正引用的那份清单；
 * 提供商远端返回的列表只是导入来源，本身不参与运行。
 *
 * <p>{@code V1__init.sql} 中 {@code (provider_id, model_name)} <em>没有</em>唯一索引，重复只能靠在
 * Service 事务内「先查后插」来防；因此并发导入时依赖事务边界，不能想当然认为数据库会兜住。
 * {@code params} 是 {@code jsonb} 列，按原始 JSON 字符串映射（与 {@code KnowledgeBase.chunkConfig} 一致），
 * 交给 Jackson 处理反而会多一层无意义的转换。{@code dimension} 只对
 * {@link ModelCapability#EMBEDDING} 有意义，且必须等于 {@code app.embedding.dimension}。</p>
 */
@Entity
@Table(name = "models")
public class ModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属提供商。 */
    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    /** 模型名，必须与提供商侧完全一致，否则调用会返回模型不存在。 */
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    /** 用途：对话或向量；创建后不可变更，否则已有引用会指向错误类型的模型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 16)
    private ModelCapability capability;

    /** 向量维度，仅向量模型有值，且必须与 pgvector 列定义一致。 */
    @Column(name = "dimension")
    private Integer dimension;

    /** 调用参数（temperature 等）的原始 JSON，默认空对象以便空值安全。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false)
    private String params = "{}";

    /** 是否启用；停用后不能再被新的配置选中。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 创建时间，由 Hibernate 在插入时填充。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 需要的无参构造，业务代码不应使用。 */
    protected ModelConfig() {
        // 供 JPA 使用
    }

    /**
     * @param providerId 所属提供商
     * @param modelName  模型名
     * @param capability 用途
     * @param dimension  向量维度，对话模型传 {@code null}
     */
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
