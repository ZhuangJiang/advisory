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
 * A model provider (table {@code model_providers}).
 *
 * <p>Column definitions must match {@code V1__init.sql} exactly because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate}; {@code created_at} carries a database
 * default, so {@link CreationTimestamp} makes Hibernate supply the value on insert instead of
 * sending {@code NULL}.</p>
 *
 * <p>{@code apiKeyCipher} is an AES-GCM ciphertext produced by
 * {@link com.advisory.provider.service.CryptoService} (Base64 of {@code iv ‖ ciphertext+tag}); it
 * must never leave the service layer — responses only ever expose a masked form of the decrypted
 * key.</p>
 */
@Entity
@Table(name = "model_providers")
public class ModelProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private ProviderType type;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "api_key_cipher", columnDefinition = "text")
    private String apiKeyCipher;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModelProvider() {
        // for JPA
    }

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
