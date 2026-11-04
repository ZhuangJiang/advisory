package com.advisory.provider.api.dto;

import com.advisory.provider.service.ProbeResult;

import java.util.List;

/**
 * Payload of {@code GET /api/providers/{id}/models?remote=true}: the probe outcome with the remote
 * names under {@code models} (the local-row payload of the same endpoint without {@code remote} is
 * a plain list of {@link ModelResponse}).
 */
public record RemoteModelsResponse(boolean ok, String errorCode, List<String> models) {

    public static RemoteModelsResponse from(ProbeResult probeResult) {
        return new RemoteModelsResponse(probeResult.ok(), probeResult.errorCode(),
                probeResult.remoteModels());
    }
}
