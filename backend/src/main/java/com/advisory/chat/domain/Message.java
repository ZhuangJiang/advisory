package com.advisory.chat.domain;

import com.advisory.chat.orchestration.TokenUsage;
import com.advisory.knowledge.retrieval.RetrievedChunk;

import java.time.Instant;
import java.util.List;

/**
 * A row of {@code messages}.
 *
 * <p>{@code content} is {@code null} while an assistant message is still {@code PENDING}.
 * {@code citations} is the exact list handed to the ANSWER prompt, so {@code [n]} in the answer
 * always indexes {@code citations[n-1]} — that is why the whole chunk is stored rather than only
 * its ids (a deleted document must not turn an old answer's reference into a dangling id).</p>
 */
public record Message(long id, long conversationId, String role, String content,
                      List<RetrievedChunk> citations, MessageStatus status, TokenUsage tokenUsage,
                      Long latencyMs, Instant createdAt) {

    public Message {
        citations = citations == null ? List.of() : List.copyOf(citations);
        tokenUsage = tokenUsage == null ? TokenUsage.ZERO : tokenUsage;
    }
}
