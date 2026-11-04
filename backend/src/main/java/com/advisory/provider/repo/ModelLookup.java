package com.advisory.provider.repo;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * The single read of a {@code models} row joined to its provider.
 *
 * <p>Both client factories ({@code EmbeddingClientFactory} for ingest, {@code ChatClientFactory} for
 * the answer path) need the same nine fields; keeping the SQL in one place means a schema change
 * cannot leave one of the two behind. This class only reads — capability/availability checks and
 * client construction stay in the factories, because the two paths word their errors differently.</p>
 *
 * <p>Not a bean on purpose: it is built from the {@link JdbcTemplate} the factories already hold, so
 * their constructors (and the unit tests that pass a {@code null} template and override the factory
 * method) keep working unchanged.</p>
 */
public class ModelLookup {

    private static final String MODEL_SQL = """
            SELECT m.model_name,
                   m.capability,
                   m.dimension,
                   m.enabled,
                   p.id             AS provider_id,
                   p.type           AS provider_type,
                   p.base_url       AS base_url,
                   p.enabled        AS provider_enabled,
                   p.api_key_cipher AS api_key_cipher
            FROM models m
            JOIN model_providers p ON p.id = m.provider_id
            WHERE m.id = ?
            """;

    private final JdbcTemplate jdbc;

    public ModelLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ModelRow> find(long modelId) {
        List<ModelRow> rows = jdbc.query(MODEL_SQL, (rs, rowNum) -> new ModelRow(
                rs.getString("model_name"),
                rs.getString("capability"),
                (Integer) rs.getObject("dimension"),
                rs.getBoolean("enabled"),
                rs.getLong("provider_id"),
                rs.getString("provider_type"),
                rs.getString("base_url"),
                rs.getBoolean("provider_enabled"),
                rs.getString("api_key_cipher")), modelId);
        return rows.stream().findFirst();
    }

    /**
     * @param apiKeyCipher the AES-GCM ciphertext as stored; never a usable credential by itself
     */
    public record ModelRow(String modelName,
                           String capability,
                           Integer dimension,
                           boolean enabled,
                           long providerId,
                           String providerType,
                           String baseUrl,
                           boolean providerEnabled,
                           String apiKeyCipher) {
    }
}
