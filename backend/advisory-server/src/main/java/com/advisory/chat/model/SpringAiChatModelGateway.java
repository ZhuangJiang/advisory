package com.advisory.chat.model;

import com.advisory.chat.mcp.McpProperties;
import com.advisory.chat.mcp.McpToolRegistry;
import com.advisory.chat.orchestration.ChatModelGateway;
import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产环境使用的 {@link ChatModelGateway}：按 {@code models} 行构造 Spring AI {@code ChatModel}。
 *
 * <p>两个入口都是阻塞调用；编排器已经把每次调用放在自带 60 秒预算的线程里，因此本类不额外做重试
 * （与入库链路不同，这里的重试会悄悄吃掉整步预算，反而把可控的超时变成不可控）。</p>
 *
 * <p>结构化输出借助 Spring AI 的 {@link BeanOutputConverter}：格式说明追加到用户提示词末尾，再把原始回答
 * 转换成 {@code type}。凡是转换失败的——纯散文、被代码围栏包住、JSON 被截断——都抛 {@link AppException}，
 * 编排器据此判定「证据不足」并降级作答，而不是让整条会话失败（spec §11）。</p>
 *
 * <p>提供商侧失败（主机不可达、401、模型不存在）统一包成 {@link ErrorCode#INTERNAL_ERROR}，会话以
 * {@code FAILED} 结束并带上提供商的原始信息，避免只留一个没有上下文的 500。token 用量尚未统计：
 * {@code ChatModelGateway} 没有用量通道，落库的 {@code token_usage} 恒为 0——这一点作为已知缺口记录在
 * 任务报告里，而不是随便估一个数字。</p>
 */
public class SpringAiChatModelGateway implements ChatModelGateway {

    private final ChatClientFactory clientFactory;
    private final McpToolRegistry toolRegistry;

    public SpringAiChatModelGateway(ChatClientFactory clientFactory) {
        this(clientFactory, new McpToolRegistry(McpProperties.disabled()));
    }

    public SpringAiChatModelGateway(ChatClientFactory clientFactory, McpToolRegistry toolRegistry) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
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
    public boolean hasLiveTools() {
        return toolRegistry.available();
    }

    /**
     * 带工具作答：把 MCP 工具注册给 Spring AI 的 {@code ChatClient}，由它完成
     * 「模型要求调用 → 执行工具 → 带结果再问一次」的循环。
     *
     * <p>无工具可用时退回普通作答，编排器的工具分支因此不需要再做一次判空。</p>
     *
     * <p>只有<em>工具执行</em>失败才断开 MCP 连接：模型侧故障（不可达、401、不支持 function calling）
     * 与 MCP 服务是否健康无关，拿它去重连只会让每个问题都多一次无谓握手。</p>
     */
    @Override
    public String chatWithTools(Long modelId, BigDecimal temperature, String systemPrompt,
                                String userPrompt) {
        if (modelId == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "必须指定对话模型");
        }
        List<ToolCallback> tools = toolRegistry.toolCallbacks();
        if (tools.isEmpty()) {
            return complete(modelId, temperature, systemPrompt, userPrompt);
        }

        ChatClientFactory.ChatClient client = clientFactory.forChat(modelId, temperature);
        // 数一下工具到底被调了几次：有些模型（如 deepseek-r1）会接受 tools 参数却从不调用，
        // 然后凭记忆编一段天气。这种答案比「查不到」更糟，因此没调用就不算工具作答。
        AtomicInteger invocations = new AtomicInteger();
        List<ToolCallback> countingTools = tools.stream()
                .map(tool -> (ToolCallback) new CountingToolCallback(tool, invocations))
                .toList();
        ChatClient chatClient = ChatClient.builder(client.model())
                .defaultOptions(client.options())
                .defaultToolCallbacks(countingTools)
                .build();
        try {
            String text = chatClient.prompt()
                    .system(systemPrompt == null ? "" : systemPrompt)
                    .user(userPrompt == null ? "" : userPrompt)
                    .call()
                    .content();
            if (text == null || text.isBlank()) {
                throw new AppException(ErrorCode.INTERNAL_ERROR, "模型返回了空响应");
            }
            if (invocations.get() == 0) {
                throw new AppException(ErrorCode.INTERNAL_ERROR,
                        "模型没有调用任何工具，无法获得实时数据");
            }
            return text;
        } catch (AppException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isToolExecutionFailure(e)) {
                toolRegistry.invalidate();
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new AppException(ErrorCode.INTERNAL_ERROR,
                    "工具调用失败 (model id=" + modelId + "): " + truncate(reason));
        }
    }

    /** 只用来统计调用次数，其余行为原样透传。 */
    private record CountingToolCallback(ToolCallback delegate, AtomicInteger invocations)
            implements ToolCallback {

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            invocations.incrementAndGet();
            return delegate.call(toolInput);
        }
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
                throw new IllegalStateException("模型返回的结构化内容为空");
            }
            return value;
        } catch (RuntimeException e) {
            // 有意不打印答案正文：它可能包含检索到的文档原文，属于敏感内容。
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "模型输出无法解析为 " + type.getSimpleName() + ": " + truncate(reason));
        }
    }

    private String complete(Long modelId, BigDecimal temperature, String systemPrompt,
                            String userPrompt) {
        if (modelId == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "必须指定对话模型");
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
            if (isContextOverflow(e)) {
                // 单独的错误码，编排器据此降级（REFLECT 收尾、ANSWER 给出可操作提示），而不是整条会话失败。
                throw new AppException(ErrorCode.MODEL_CONTEXT_EXCEEDED,
                        "模型上下文不足 (model id=" + modelId + "): " + truncate(reason));
            }
            throw new AppException(ErrorCode.INTERNAL_ERROR,
                    "模型调用失败 (model id=" + modelId + "): " + truncate(reason));
        }
    }

    /**
     * 判断提供商是否在抱怨「prompt 超过上下文窗口」。     *
     * <p>各家措辞不同：Ollama 是 {@code exceed_context_size_error} / {@code exceeds the available
     * context size}，OpenAI 兼容端点是 {@code maximum context length}。判定要看整条 cause 链，
     * 因为 Spring AI 的 {@code NonTransientAiException} 可能把响应体包在里层。</p>
     */
    private static boolean isContextOverflow(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("exceed_context_size")
                    || lower.contains("exceeds the available context size")
                    || lower.contains("context length")
                    || lower.contains("context size")) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    /**
     * 失败是否出在「执行工具」这一步（而不是模型调用本身）。
     *
     * <p>Spring AI 用 {@code ToolExecutionException} 包装工具侧的任何失败，包括 MCP 传输断开、
     * 工具抛异常、参数不符合 schema。只有这一类才值得重连 MCP；模型侧的错误（401、模型不存在、
     * 该模型不支持 function calling）与工具服务健康与否无关。</p>
     */
    private static boolean isToolExecutionFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ToolExecutionException) {
                return true;
            }
        }
        return false;
    }
}
