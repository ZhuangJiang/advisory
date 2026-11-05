package com.advisory.common.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * JDBC 仓储的公共基类：统一持有 {@link JdbcTemplate} 与 {@link ObjectMapper}，并提供 jsonb 列的读写助手。
 *
 * <p>这两个依赖本来就是容器里的单例（{@code JdbcTemplate} 线程安全，可以并发共享），仓储只需引用，
 * 不应各自再声明一遍构造参数——那会让每新增一个仓储就重复同样的两行装配，也让「连接从哪来」
 * 这个问题散落到十几个类里。依赖通过基类的 setter 注入，子类因此不再需要构造器。</p>
 *
 * <p>JSON 助手把两种失败语义分开，避免调用方随手 catch 掉本该暴露的问题：
 * <ul>
 *   <li>{@link #toJson} 与 {@link #fromJson} 用于持久化路径，失败即抛 {@link IllegalStateException}，
 *       让事务回滚——一个写坏的 jsonb 不该被当成正常数据继续流转；</li>
 *   <li>{@link #tryFromJson} 用于读取展示性数据（引用列表、token 用量），解析失败时降级为空，
 *       因为「少显示一份引用」比「整个回答加载失败」更可接受。用哪个方法本身就是一种意图声明。</li>
 * </ul>
 * 子类使用前无需关心初始化：{@code jdbc} 与 {@code json} 在容器完成装配后即可用。</p>
 */
public abstract class JdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcRepository.class);

    /** 容器共享的 JDBC 入口。不要在子类里自行创建。 */
    protected JdbcTemplate jdbc;

    /** 容器共享的 JSON 映射器，用于 {@code jsonb} 列与快照字段。 */
    protected ObjectMapper json;

    /**
     * 由 Spring 在实例化后注入依赖，子类因此不必声明构造器。
     *
     * @param jdbc JDBC 入口
     * @param json JSON 映射器
     */
    @Autowired
    void injectInfrastructure(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 把对象序列化为可写入 {@code jsonb} 列的文本。
     *
     * @param value 待序列化的值
     * @return JSON 文本
     * @throws IllegalStateException 序列化失败；这属于编码错误，应当中断当前事务
     */
    protected String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * 解析 {@code jsonb} 列的文本，失败即抛。
     *
     * @param raw  数据库取出的文本
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 解析结果
     * @throws IllegalStateException 文本不是合法 JSON，或结构与目标类型不符
     */
    protected <T> T fromJson(String raw, TypeReference<T> type) {
        return fromJson(raw, type, "JSON 列");
    }

    /**
     * 解析 {@code jsonb} 列的文本，失败时把出错的列写进异常信息，便于定位是哪一行数据坏了。
     *
     * @param raw   数据库取出的文本
     * @param type  目标类型
     * @param field 列的全名，例如 {@code agents.kb_ids}
     * @param <T>   目标类型
     * @return 解析结果
     * @throws IllegalStateException 文本不是合法 JSON，或结构与目标类型不符
     */
    protected <T> T fromJson(String raw, TypeReference<T> type, String field) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(field + " 中存的不是合法 JSON", e);
        }
    }

    /**
     * 解析 {@code jsonb} 列的文本（目标类型为普通类而非泛型）。
     *
     * @param raw   数据库取出的文本
     * @param type  目标类型
     * @param field 列的全名，用于异常信息
     * @param <T>   目标类型
     * @return 解析结果
     * @throws IllegalStateException 文本不是合法 JSON，或结构与目标类型不符
     */
    protected <T> T fromJson(String raw, Class<T> type, String field) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(field + " 中存的不是合法 JSON", e);
        }
    }

    /**
     * 解析展示性数据；失败时降级为空而不是抛异常。
     *
     * <p>只用于「读不出来也不影响主流程」的字段（引用、用量）。持久化路径请用 {@link #fromJson}。</p>
     *
     * @param raw  数据库取出的文本，可为空
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 解析结果；文本为空或解析失败时为空
     */
    protected <T> Optional<T> tryFromJson(String raw, TypeReference<T> type) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readValue(raw, type));
        } catch (JsonProcessingException e) {
            // 降级是调用方明确选择的语义，但仍要留下线索，否则「引用忽然消失」无从追查。
            log.debug("JSON 解析失败，按空值降级: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
