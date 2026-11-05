package com.advisory.provider.domain;

/**
 * 提供商通信协议：自建 Ollama 服务，或兼容 OpenAI 的 HTTP API。
 * 以 {@code VARCHAR(32)} 存储，{@code V1__init.sql} 中有 CHECK 约束。
 */
public enum ProviderType {

    /** Ollama 原生接口：模型列表走 {@code /api/tags}，不需要 Bearer 鉴权。 */
    OLLAMA,

    /** OpenAI 兼容接口：模型列表走 {@code /v1/models}，按需附带 Bearer 鉴权。 */
    OPENAI_COMPATIBLE
}
