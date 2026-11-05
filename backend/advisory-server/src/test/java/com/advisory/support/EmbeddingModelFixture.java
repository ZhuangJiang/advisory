package com.advisory.support;

import com.advisory.provider.service.CryptoService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 每个用例造一组 {@code model_providers} + {@code models}（EMBEDDING 类型）行，
 * 并通过 {@code knowledge_bases.embedding_model_id} 挂到某个知识库上。
 *
 * <p>名称带 UUID 后缀以保证唯一；数据行由 {@link #close()} 在 {@code @AfterEach} 中删除。
 * 引用该模型的知识库必须先删（外键为 {@code ON DELETE RESTRICT}），
 * 这一步由同一段清理逻辑在关闭夹具之前完成。</p>
 *
 * <p>API Key 列存的是经 {@link CryptoService#encrypt(String)} 得到的真实密文，格式与生产环境一致：
 * 夹具绝不把明文留在 {@code api_key_cipher} 里，而任何走到真实网关的成功路径用例也都能正常解密。</p>
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

    /** 提供商类型取 {@code OPENAI_COMPATIBLE}；测试里的桩网关从不真的连它。 */
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

    /** 把 {@code kbId} 对应知识库的 {@code knowledge_bases.embedding_model_id} 指向本模型。 */
    public void wire(long kbId) {
        jdbc.update("UPDATE knowledge_bases SET embedding_model_id = ? WHERE id = ?", modelId, kbId);
    }

    /** 删除模型行与提供商行；引用它的知识库此时必须已被清掉。 */
    @Override
    public void close() {
        jdbc.update("DELETE FROM models WHERE id = ?", modelId);
        jdbc.update("DELETE FROM model_providers WHERE id = ?", providerId);
    }
}
