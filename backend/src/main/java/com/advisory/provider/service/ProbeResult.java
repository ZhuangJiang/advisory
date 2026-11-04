package com.advisory.provider.service;

import java.util.List;

/**
 * 提供商连通性探测的结果。
 *
 * <p>探测本身不抛异常：所有提供商侧的问题都折算成 {@code ok=false} 加一个错误码，
 * 便于调用方按码分支而不是解析异常文本。</p>
 *
 * @param ok           提供商返回 2xx 且模型列表可解析时为真；若指定了模型名，还要求该名字在列表中
 * @param errorCode    {@link #NETWORK_UNREACHABLE}、{@link #AUTH_FAILED}、{@link #MODEL_NOT_FOUND}
 *                     或 {@link #TIMEOUT}；恰好在 {@code ok} 为真时为 {@code null}
 * @param remoteModels 能读到时为远端模型名列表，否则为空列表
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
