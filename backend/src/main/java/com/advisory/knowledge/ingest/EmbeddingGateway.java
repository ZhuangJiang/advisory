package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;

import java.util.List;

/**
 * 用某个已配置的向量模型（{@code models.id}）把文本转换成向量。
 *
 * <ul>
 *   <li>每次调用批量处理 32 条文本；失败重试两次，退避 1 秒、2 秒；单次调用超时 60 秒。</li>
 *   <li>每个返回向量都要与 {@code app.embedding.dimension} 比对；不一致则整次调用以
 *       {@link ErrorCode#EMBEDDING_DIMENSION_MISMATCH} 失败，且调用方此时不应写入任何数据
 *       （片段落库只在本方法返回之后才发生）。</li>
 *   <li>绝不打印文本内容：文档可能敏感，失败日志只携带异常元数据。</li>
 * </ul>
 */
public interface EmbeddingGateway {

    /**
     * 批量向量化。
     *
     * @param embeddingModelId {@code models.id}，由实现方解析出
     *                         {@code model_providers.type/base_url} 与 {@code models.model_name}
     * @param texts            片段文本，按文档顺序
     * @return 与文本一一对应的向量，顺序相同，每个长度均为 {@code app.embedding.dimension}
     * @throws AppException 向量长度不符时抛 {@code EMBEDDING_DIMENSION_MISMATCH}（40010）；
     *                      模型无法解析为已启用的 {@code EMBEDDING} 模型时抛 {@code VALIDATION_FAILED}
     */
    List<float[]> embed(Long embeddingModelId, List<String> texts);
}
