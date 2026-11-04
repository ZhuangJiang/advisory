package com.advisory.support;

import com.advisory.provider.service.CryptoService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Creates one {@code model_providers} + {@code models} (EMBEDDING) row pair per test and wires it
 * into a knowledge base via {@code knowledge_bases.embedding_model_id}.
 *
 * <p>Names are unique (UUID suffix) and rows are deleted by {@link #close()} in {@code @AfterEach};
 * knowledge bases referencing the model must be deleted first ({@code ON DELETE RESTRICT}), which
 * the same cleanup block does before closing the fixture.</p>
 *
 * <p>The API key column stores a real {@link CryptoService#encrypt(String)} ciphertext — the same
 * format production writes — so the fixture never leaves plaintext in {@code api_key_cipher} and
 * any success-path test that reaches the real gateway can decrypt it.</p>
 */
public final class EmbeddingModelFixture implements AutoCloseable {

    private final JdbcTemplate jdbc;
    private final long providerId;
    private final long modelId;

    private EmbeddingModelFixture(JdbcTemplate jdbc, long providerId, long modelId) {
        this.jdbc = jdbc;
        this.providerId = providerId;
        this.modelId = modelId;
    }

    /** Provider type is {@code OPENAI_COMPATIBLE}; the stub gateway in tests never contacts it. */
    public static EmbeddingModelFixture create(JdbcTemplate jdbc, CryptoService cryptoService) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Long providerId = jdbc.queryForObject("""
                INSERT INTO model_providers (name, type, base_url, api_key_cipher)
                VALUES (?, 'OPENAI_COMPATIBLE', 'http://127.0.0.1:9', ?)
                RETURNING id
                """, Long.class, "provider-" + suffix,
                cryptoService.encrypt("test-api-key"));
        Long modelId = jdbc.queryForObject("""
                INSERT INTO models (provider_id, model_name, capability, dimension)
                VALUES (?, ?, 'EMBEDDING', 1024)
                RETURNING id
                """, Long.class, providerId, "embed-model-" + suffix);
        if (providerId == null || modelId == null) {
            throw new IllegalStateException("embedding model fixture insert did not return ids");
        }
        return new EmbeddingModelFixture(jdbc, providerId, modelId);
    }

    public long modelId() {
        return modelId;
    }

    /** Points {@code knowledge_bases.embedding_model_id} of {@code kbId} at this model. */
    public void wire(long kbId) {
        jdbc.update("UPDATE knowledge_bases SET embedding_model_id = ? WHERE id = ?", modelId, kbId);
    }

    /** Deletes the model and provider rows; referencing knowledge bases must be gone already. */
    @Override
    public void close() {
        jdbc.update("DELETE FROM models WHERE id = ?", modelId);
        jdbc.update("DELETE FROM model_providers WHERE id = ?", providerId);
    }
}
