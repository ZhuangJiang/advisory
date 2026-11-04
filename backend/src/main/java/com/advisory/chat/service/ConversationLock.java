package com.advisory.chat.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One in-flight question per conversation (spec §11: the second concurrent question is 409
 * {@code CONVERSATION_BUSY}).
 *
 * <p>In-process on purpose: the platform runs as a single replica (spec §2), so a distributed lock
 * would add infrastructure without buying anything. The set is released in the worker's
 * {@code finally}, which also covers a failed or disconnected run — a crashed request can never
 * wedge a conversation, and a restart clears every lock.</p>
 */
@Component
public class ConversationLock {

    private final Set<Long> busy = ConcurrentHashMap.newKeySet();

    public boolean tryAcquire(Long conversationId) {
        return conversationId != null && busy.add(conversationId);
    }

    public void release(Long conversationId) {
        if (conversationId != null) {
            busy.remove(conversationId);
        }
    }
}
