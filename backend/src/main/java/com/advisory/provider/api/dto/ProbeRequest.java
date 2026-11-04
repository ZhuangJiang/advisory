package com.advisory.provider.api.dto;

/** {@code POST /api/providers/{id}/test} 的可选请求体：顺带确认某个模型名是否存在。 */
public record ProbeRequest(String modelName) {
}
