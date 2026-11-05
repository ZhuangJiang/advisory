package com.advisory.provider.repo;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 读取一行 {@code models} 并连带其提供商的唯一入口。
 *
 * <p>两个客户端工厂（入库用的 {@code EmbeddingClientFactory}、作答用的 {@code ChatClientFactory}）
 * 需要同样的九个字段，SQL 收在一处才能保证以后改表不会只改到其中一个。
 * 本类只负责读：能力校验、可用性判断与客户端构造留在各自工厂里，因为两条链路的报错措辞并不相同。</p>
 *
 * <p>故意不做成 bean：由工厂现有的 {@link JdbcTemplate} 直接构造，这样工厂的构造签名不用变，
 * 那些传入 {@code null} 模板并覆写工厂方法的单元测试也无需改。</p>
 */
public class ModelLookup {

    /** 模型与其提供商的一次性读取，字段顺序与 {@link ModelRow} 一致。 */
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

    /**
     * @param jdbc 用于执行查询的模板
     */
    public ModelLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按模型主键读取。
     *
     * @param modelId 模型主键
     * @return 匹配的行；模型或提供商不存在时为空（{@code JOIN} 天然过滤）
     */
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
     * 一行模型连同提供商的投影。
     *
     * @param modelName       模型名
     * @param capability      {@code CHAT} 或 {@code EMBEDDING} 的字符串形式
     * @param dimension       向量维度，对话模型为空
     * @param enabled         模型是否启用
     * @param providerId      提供商主键
     * @param providerType    协议类型字符串
     * @param baseUrl         提供商基础地址
     * @param providerEnabled 提供商是否启用
     * @param apiKeyCipher    库中原样的 AES-GCM 密文，本身不可用作凭证
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
