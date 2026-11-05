package com.advisory.provider.repo;

import com.advisory.provider.domain.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@link ModelConfig} 的数据访问入口。
 *
 * <p>刻意不提供派生的 {@code existsByProviderIdAndModelName}：既然 {@code (provider_id, model_name)}
 * 上没有唯一索引，查重就必须与插入处于同一事务（先查后插），否则两个并发请求会双写成功。
 * 因此查重统一走 {@link #findByProviderIdAndModelName}，并在写入事务内调用。</p>
 */
public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {

    /**
     * 按提供商列出模型，按主键升序保证界面顺序稳定。
     *
     * @param providerId 提供商主键
     * @return 模型列表
     */
    List<ModelConfig> findByProviderIdOrderByIdAsc(Long providerId);

    /**
     * 按「提供商 + 模型名」查找，用于导入去重；返回列表而非 Optional，是为了容忍历史脏数据里
     * 可能已存在的重复行而不至于直接抛异常。
     *
     * @param providerId 提供商主键
     * @param modelName  模型名
     * @return 匹配的模型行
     */
    List<ModelConfig> findByProviderIdAndModelName(Long providerId, String modelName);

    /**
     * 统计某提供商下的模型数，用于列表页展示。
     *
     * @param providerId 提供商主键
     * @return 模型数量
     */
    long countByProviderId(Long providerId);
}
