package com.advisory.chat.domain;

import com.advisory.chat.orchestration.TokenUsage;
import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.time.Instant;
import java.util.List;

/**
 * {@code messages} 表的一行。
 *
 * <p>助手消息处于 {@code PENDING} 时 {@code content} 为 {@code null}。{@code citations} 就是当时交给
 * ANSWER 提示词的那份列表，所以答案里的 {@code [n]} 永远索引到 {@code citations[n-1]}——这也是为什么存的是
 * 片段全文而不只是 id：文档被删掉后，旧答案的引用不能变成一个悬空 id。</p>
 */
public record Message(long id, long conversationId, String role, String content,
                      List<RetrievedChunk> citations, MessageStatus status, TokenUsage tokenUsage,
                      Long latencyMs, Instant createdAt) {

    public Message {
        citations = citations == null ? List.of() : List.copyOf(citations);
        tokenUsage = tokenUsage == null ? TokenUsage.ZERO : tokenUsage;
    }
}
