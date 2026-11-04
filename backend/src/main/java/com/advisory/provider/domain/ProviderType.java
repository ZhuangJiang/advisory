package com.advisory.provider.domain;

/**
 * Provider transport: a self-hosted Ollama daemon or an OpenAI-compatible HTTP API. Stored as a
 * {@code VARCHAR(32)} with a check constraint in {@code V1__init.sql}.
 */
public enum ProviderType {
    OLLAMA,
    OPENAI_COMPATIBLE
}
