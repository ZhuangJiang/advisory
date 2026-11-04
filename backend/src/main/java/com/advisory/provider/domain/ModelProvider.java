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

import java.time.Instant;

/**
 * 模型提供商（表 {@code model_providers}）。
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto} 为 {@code validate}，列定义必须与 {@code V1__init.sql} 完全一致；
 * {@code created_at} 在库里有默认值，用 {@link CreationTimestamp} 让 Hibernate 在插入时显式赋值，
 * 避免发送 {@code NULL} 覆盖默认值。</p>
 *
 * <p>{@code apiKeyCipher} 是 {@link com.advisory.provider.service.CryptoService} 产出的 AES-GCM 密文
 * （{@code Base64(iv ‖ ciphertext+tag)}），绝不允许离开服务层：对外响应只暴露解密后密钥的掩码形式，
 * 密文与明文都不出现在任何接口返回中。</p>
 */
@Entity
@Table(name = "model_providers")
public class ModelProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示名，仅用于界面识别。 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 通信协议类型，决定探测与调用时拼接的路径。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private ProviderType type;

    /** 基础地址，例如 {@code http://ollama:11434}，末尾斜杠由调用方归一化。 */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /** 加密后的 API Key；为空表示该提供商无需鉴权（如内网 Ollama）。 */
    @Column(name = "api_key_cipher", columnDefinition = "text")
    private String apiKeyCipher;

    /** 是否启用；停用后其下模型不会再被探测或调用。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 创建时间，由 Hibernate 在插入时填充。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 需要的无参构造，业务代码不应使用。 */
    protected ModelProvider() {
        // 供 JPA 使用
    }

    /**
     * @param name    展示名
     * @param type    通信协议类型
     * @param baseUrl 基础地址
     */
    public ModelProvider(String name, ProviderType type, String baseUrl) {
        this.name = name;
        this.type = type;
        this.baseUrl = baseUrl;
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

    public ProviderType getType() {
        return type;
    }

    public void setType(ProviderType type) {
        this.type = type;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(String apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
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
