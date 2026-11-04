package com.advisory.provider.api.dto;

import com.advisory.provider.service.ProbeResult;

import java.util.List;

/**
 * {@code GET /api/providers/{id}/models?remote=true} 的返回体：探测结果，远端模型名放在
 * {@code models} 下。
 *
 * <p>同一个地址不带 {@code remote} 时返回的是 {@link ModelResponse} 的普通列表，两者结构不同，
 * 前端按是否请求远端来区分解析。</p>
 */
public record RemoteModelsResponse(boolean ok, String errorCode, List<String> models) {

    public static RemoteModelsResponse from(ProbeResult probeResult) {
        return new RemoteModelsResponse(probeResult.ok(), probeResult.errorCode(),
                probeResult.remoteModels());
    }
}
