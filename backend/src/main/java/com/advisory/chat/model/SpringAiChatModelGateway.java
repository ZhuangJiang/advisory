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
            throw new AppException(ErrorCode.INTERNAL_ERROR,
                    "模型调用失败 (model id=" + modelId + "): " + truncate(reason));
        }
    }

    private static String truncate(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
