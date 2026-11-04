package com.advisory.provider.api.dto;

import com.advisory.provider.domain.ModelCapability;

/**
 * One entry of an import request: a remote model selected for copying into the local
 * {@code models} table, with the capability (and dimension for embeddings) the operator assigns.
 */
public record ImportModelSpec(
        String modelName,
        ModelCapability capability,
        Integer dimension) {
}
