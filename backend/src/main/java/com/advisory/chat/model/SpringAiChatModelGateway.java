package com.advisory.chat.model;

import com.advisory.chat.orchestration.ChatModelGateway;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The production {@link ChatModelGateway}: Spring AI {@code ChatModel} built per {@code models} row.
 *
 * <p>Both entry points are blocking; the orchestrator already wraps every call in a 60s step budget
 * on its own thread, so this class adds no retry logic of its own (unlike the embedding path, a
 * retry here would silently eat the step budget).</p>
 *
 * <p>Structured output uses Spring AI's {@link BeanOutputConverter}: the format instructions are
 * appended to the user prompt and the raw answer is converted into {@code type}. Anything that does
 * not convert — prose, a code fence, truncated JSON — becomes an {@link AppException}, which the
 * orchestrator treats as "evidence insufficient" and degrades instead of failing the conversation
 * (spec §11).</p>
 *
 * <p>Provider failures (unreachable host, 401, unknown model) are wrapped into
 * {@link ErrorCode#INTERNAL_ERROR}; the conversation then ends as {@code FAILED} with the provider
 * message, never a 500 without context. Usage is not aggregated yet: {@code ChatModelGateway} has no
 * usage channel, so the persisted {@code token_usage} stays zero — recorded in the task report as a
 * known gap rather than silently guessed.</p>
 */
public class SpringAiChatModelGateway implements ChatModelGateway {

    private final ChatClientFactory clientFactory;

    public SpringAiChatModelGateway(ChatClientFactory clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    @Override
    public String chat(Long modelId, String systemPrompt, String userPrompt) {
        return complete(modelId, null, systemPrompt, userPrompt);
    }

    @Override
    public String chat(Long modelId, BigDecimal temperature, String systemPrompt, String userPrompt) {
        return complete(modelId, temperature, systemPrompt, userPrompt);
    }

    @Override
    public <T> T chatStructured(Long modelId, String systemPrompt, String userPrompt, Class<T> type) {
        return structured(modelId, null, systemPrompt, userPrompt, type);
    }

    @Override
    public <T> T chatStructured(Long modelId, BigDecimal temperature, String systemPrompt,
                                String userPrompt, Class<T> type) {
        return structured(modelId, temperature, systemPrompt, userPrompt, type);
    }

    private <T> T structured(Long modelId, BigDecimal temperature, String systemPrompt,
                             String userPrompt, Class<T> type) {
        Objects.requireNonNull(type, "type");
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String raw = complete(modelId, temperature, systemPrompt,
                userPrompt + "\n\n" + converter.getFormat());
        try {
            T value = converter.convert(raw);
            if (value == null) {
                throw new IllegalStateException("empty structured output");
            }
            return value;
        } catch (RuntimeException e) {
            // The answer text is deliberately not logged: it may echo retrieved document content.
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "模型输出无法解析为 " + type.getSimpleName() + ": " + truncate(reason));
        }
    }

    private String complete(Long modelId, BigDecimal temperature, String systemPrompt,
                            String userPrompt) {
        if (modelId == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chat model id is required");
        }
        ChatClientFactory.ChatClient client = clientFactory.forChat(modelId, temperature);
        ChatModel model = client.model();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt == null ? "" : systemPrompt),
                new UserMessage(userPrompt == null ? "" : userPrompt)), client.options());
        try {
            ChatResponse response = model.call(prompt);
            String text = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null
                    : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new AppException(ErrorCode.INTERNAL_ERROR, "模型返回了空响应");
            }
            return text;
        } catch (AppException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new AppException(ErrorCode.INTERNAL_ERROR,
                    "模型调用失败 (model id=" + modelId + "): " + truncate(reason));
        }
    }

    private static String truncate(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
