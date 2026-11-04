package com.advisory.provider.api.dto;

/** Optional body of {@code POST /api/providers/{id}/test}: probe a specific model name. */
public record ProbeRequest(String modelName) {
}
