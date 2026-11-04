package com.advisory.chat.orchestration;

/**
 * 单步或整次运行的 token 记账。
 *
 * <p>当前网关只返回纯文本，因此用量恒为 0，除非将来的网关能上报（spec §11：「若提供商返回 usage，
 * 否则记 0」）。这里保留结构是为了让接口契约不随实现变化。</p>
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(promptTokens + other.promptTokens(),
                completionTokens + other.completionTokens());
    }
}
