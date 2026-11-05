package com.advisory.provider.api.dto;

import java.util.List;

/**
 * {@code POST /api/providers/{id}/models/import} 的请求体：待导入的远端模型列表。
 *
 * @param models 选中的模型及其用途
 */
public record ImportModelsRequest(List<ImportModelSpec> models) {
}
