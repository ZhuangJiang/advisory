package com.advisory.chat.orchestration;

import java.math.BigDecimal;

/**
 * 一次编排运行所需的模型访问能力。
 *
 * <p>做成接口有两个目的：状态机可以用脚本化的假实现测试（不联网、不启动 Spring AI 上下文）；生产实现则能
 * 按 {@code models} 行为每个 Agent 构造独立客户端——提供商与模型由 Agent 决定，自动配置表达不了。
 * {@link #chatStructured} 在模型输出无法解析成 {@code type} 时必须抛
 * {@link com.advisory.common.error.AppException}，编排器据此降级，而不是让会话整体失败。</p>
 *
 * <p>{@code temperature} 重载默认委托给三参数版本，假实现因此只需实现一对方法；生产网关覆写它们，
 * 把 Agent 的温度作为请求默认值。</p>
 */
public interface ChatModelGateway {

    String chat(Long modelId, String systemPrompt, String userPrompt);

    <T> T chatStructured(Long modelId, String systemPrompt, String userPrompt, Class<T> type);

    default String chat(Long modelId, BigDecimal temperature, String systemPrompt, String userPrompt) {
        return chat(modelId, systemPrompt, userPrompt);
    }

    default <T> T chatStructured(Long modelId, BigDecimal temperature, String systemPrompt,
                                 String userPrompt, Class<T> type) {
        return chatStructured(modelId, systemPrompt, userPrompt, type);
    }

    /**
     * 是否接入了实时工具（MCP）。默认 {@code false}：假实现与未配置 MCP 的环境保持接入前的行为，
     * 编排器不会为它们多走一条分支。实现方必须保证这是一次内存查询，不能在对话线程上做网络 I/O。
     */
    default boolean hasLiveTools() {
        return false;
    }

    /**
     * 带工具作答：模型可以调用外部工具（例如查天气）后再生成答案。
     *
     * <p>默认退回普通作答，因此已有实现与测试替身无需改动。生产实现会把工具回调交给 Spring AI 的
     * {@code ChatClient}，由它完成「模型要求调用 → 执行工具 → 带着结果再问一次」的循环。</p>
     */
    default String chatWithTools(Long modelId, BigDecimal temperature, String systemPrompt,
                                 String userPrompt) {
        return chat(modelId, temperature, systemPrompt, userPrompt);
    }
}
