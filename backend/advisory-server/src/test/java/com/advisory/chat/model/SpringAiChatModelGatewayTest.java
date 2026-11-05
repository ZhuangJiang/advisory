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
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 12：Spring AI 对话网关与编排器之间的契约。
 *
 * <p>不联网、不起 Spring 上下文：假的 {@link ChatClientFactory} 返回一个 {@link ChatModel}，
 * 要么吐出预设字符串、要么直接抛异常——这正是编排器必须扛住的那两种行为。
 * 这里断言的是接缝处：格式指令会被附加进去，JSON 能转成目标 record，而其它任何输出都降级为
 * {@link ErrorCode#VALIDATION_FAILED}，而不是让整个流程崩掉。</p>
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

    /**
     * Ollama 的超窗报错必须有自己的错误码：编排器据此降级成「换个更大窗口的模型」的提示，
     * 而不是把整条会话标成 FAILED。Spring AI 会把响应体包在 {@code NonTransientAiException} 里，
     * 因此判定要看整条 cause 链。
     */
    @Test
    void ollamaContextOverflowGetsItsOwnErrorCode() {
        ChatModel overflowing = prompt -> {
            throw new IllegalStateException("400 - {\"error\":\"request (5448 tokens) exceeds "
                    + "the available context size (4096 tokens), try increasing it\"}");
        };
        SpringAiChatModelGateway gateway = gateway(overflowing);

        AppException failure = assertThrows(AppException.class,
                () -> gateway.chat(15L, "sys", "问题"));

        assertEquals(ErrorCode.MODEL_CONTEXT_EXCEEDED, failure.getCode());
        assertEquals(ErrorCode.MODEL_CONTEXT_EXCEEDED.getHttpStatus(), HttpStatus.INTERNAL_SERVER_ERROR);
        assertTrue(failure.getMessage().contains("15"), "the model id is included");
    }

    @Test
    void openAiStyleContextOverflowInANestedCauseIsAlsoRecognised() {
        ChatModel overflowing = prompt -> {
            throw new RuntimeException("provider call failed",
                    new IllegalStateException("This model's maximum context length is 4096 tokens"));
        };
        SpringAiChatModelGateway gateway = gateway(overflowing);

        AppException failure = assertThrows(AppException.class,
                () -> gateway.chat(7L, "sys", "问题"));

        assertEquals(ErrorCode.MODEL_CONTEXT_EXCEEDED, failure.getCode());
    }

    // ------------------------------------------------------------------ 假实现

    private static ChatModel answering(AtomicReference<Prompt> seen, String text) {
        return prompt -> {
            seen.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        };
    }

    /** 完全不碰数据库的工厂：直接拿传入的模型构造客户端。 */
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
