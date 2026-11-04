package com.advisory.provider.service;

import java.util.List;

/**
 * Outcome of a provider connectivity probe.
 *
 * @param ok           true when the provider answered 2xx with a parseable model list (and, when a
 *                     model name was requested, that name is present)
 * @param errorCode    {@link #NETWORK_UNREACHABLE}, {@link #AUTH_FAILED}, {@link #MODEL_NOT_FOUND}
 *                     or {@link #TIMEOUT}; {@code null} exactly when {@code ok} is true
 * @param remoteModels the remote model names when they could be read, otherwise empty
 */
public record ProbeResult(boolean ok, String errorCode, List<String> remoteModels) {

    public static final String NETWORK_UNREACHABLE = "NETWORK_UNREACHABLE";
    public static final String AUTH_FAILED = "AUTH_FAILED";
    public static final String MODEL_NOT_FOUND = "MODEL_NOT_FOUND";
    public static final String TIMEOUT = "TIMEOUT";

    public ProbeResult {
        remoteModels = remoteModels == null ? List.of() : List.copyOf(remoteModels);
    }

    public static ProbeResult success(List<String> remoteModels) {
        return new ProbeResult(true, null, remoteModels);
    }

    public static ProbeResult failure(String errorCode) {
        return new ProbeResult(false, errorCode, List.of());
    }
}
