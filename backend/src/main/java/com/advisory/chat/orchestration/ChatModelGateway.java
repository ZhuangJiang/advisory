package com.advisory.chat.orchestration;

/**
 * The model access an orchestration run needs.
 *
 * <p>Kept as an interface so the state machine can be tested with a scripted fake (no network, no
 * Spring AI context) and so the production implementation can build one client per
 * {@code models} row — the provider/model is chosen by an agent, which auto-configuration cannot
 * express. {@link #chatStructured} must throw {@link com.advisory.common.error.AppException} when
 * the model answers something that does not parse into {@code type}; the orchestrator degrades
 * instead of failing the conversation.</p>
 */
public interface ChatModelGateway {

    String chat(Long modelId, String systemPrompt, String userPrompt);

    <T> T chatStructured(Long modelId, String systemPrompt, String userPrompt, Class<T> type);
}
