package com.advisory.chat.orchestration;

/**
 * Token accounting for one step or one run. The current gateway returns plain strings, so usage is
 * zero unless a future gateway reports it (spec §11: "若提供商返回 usage，否则记 0").
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(promptTokens + other.promptTokens(),
                completionTokens + other.completionTokens());
    }
}
