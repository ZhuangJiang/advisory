package com.advisory.provider.api.dto;

import java.util.List;

/** Body of {@code POST /api/providers/{id}/models/import}: the selected remote models to import. */
public record ImportModelsRequest(List<ImportModelSpec> models) {
}
