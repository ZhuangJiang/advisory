package com.advisory.chat.model;

import com.advisory.chat.orchestration.PlanResult;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 12: the Spring AI chat gateway's contract with the orchestrator.
 *
 * <p>No network and no Spring context: a fake {@link ChatClientFactory} hands back a {@link ChatModel}
 * that either answers with a canned string or throws, which is exactly the two behaviours the
 * orchestrator has to survive. What is asserted here is the seam — format instructions get appended,
 * JSON becomes the requested record, and anything else becomes a degraded
 * {@link ErrorCode#VALIDATION_FAILED} rather than a crash.</p>
 */
class SpringAiChatModelGatewayTest {

    @Test
    void structuredOutputIsConvertedIntoTheRequestedRecord() {
        AtomicReference<Prompt> seen = new AtomicReference<>();
        SpringAiChatModelGateway gateway = gateway(answering(seen,
                "{\"queries\":[\"年假 政策\",\"报销 流程\"]}"));

        PlanResult result = gateway.chatStructured(7L, new BigDecimal("0.30"), "sys", "问题",
                PlanResult.class);

        assertEquals(List.of("年假 政策", "报销 流程"), result.queries());
        assertTrue(seen.get().getInstructions().get(0).getText().contains("sys"),
                "the system prompt is sent as a system message");
        String userText = seen.get().getInstructions().get(1).getText();
        assertTrue(userText.contains("问题"), "the user prompt is preserved");
        assertTrue(userText.toLowerCase().contains("json"),
                "Spring AI format instructions are appended for structured calls");
    }

    @Test
    void proseInsteadOfJsonDegradesIntoAnAppException() {
        SpringAiChatModelGateway gateway = gateway(answering(new AtomicReference<>(),
                "抱歉，我无法回答这个问题。"));

        AppException failure = assertThrows(AppException.class, () ->
                gateway.chatStructured(7L, null, "sys", "问题", PlanResult.class));
        assertEquals(ErrorCode.VALIDATION_FAILED, failure.getCode());
        assertTrue(failure.getMessage().contains("PlanResult"),
                "the message names the target type so the cause is obvious in the logs");
    }

    @Test
    void providerFailureIsWrappedWithTheModelId() {
        ChatModel failing = prompt -> {
            throw new IllegalStateException("401 Unauthorized");
        };
        SpringAiChatModelGateway gateway = gateway(failing);

        AppException failure = assertThrows(AppException.class,
                () -> gateway.chat(7L, "sys", "问题"));
        assertEquals(ErrorCode.INTERNAL_ERROR, failure.getCode());
        assertTrue(failure.getMessage().contains("401"), "the provider message is preserved");
        assertTrue(failure.getMessage().contains("7"), "the model id is included");
    }

    @Test
    void blankAnswerIsTreatedAsAFailure() {
        SpringAiChatModelGateway gateway = gateway(answering(new AtomicReference<>(), "   "));

        AppException failure = assertThrows(AppException.class,
                () -> gateway.chat(7L, "sys", "问题"));
        assertEquals(ErrorCode.INTERNAL_ERROR, failure.getCode());
    }

    // ------------------------------------------------------------------ fakes

    private static ChatModel answering(AtomicReference<Prompt> seen, String text) {
        return prompt -> {
            seen.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        };
    }

    /** A factory that never touches a database: the client is built from the supplied model. */
    private static SpringAiChatModelGateway gateway(ChatModel model) {
        ChatOptions options = OpenAiChatOptions.builder().model("stub-model").build();
        ChatClientFactory factory = new ChatClientFactory(null, null) {
            @Override
            public ChatClient forChat(long chatModelId, BigDecimal temperature) {
                return new ChatClient(model, options);
            }
        };
        return new SpringAiChatModelGateway(factory);
    }
}
