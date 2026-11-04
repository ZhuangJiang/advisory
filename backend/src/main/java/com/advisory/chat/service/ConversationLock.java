package com.advisory.chat.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个会话同时只允许一个问题在跑（spec §11：第二个并发提问返回 409 {@code CONVERSATION_BUSY}）。
 *
 * <p>刻意做在进程内：平台以单副本运行（spec §2），引入分布式锁只是徒增基础设施。
 * 锁在任务的 {@code finally} 中释放，失败或断线同样走这条路径——请求崩掉不会把会话卡死，
 * 进程重启则天然清空全部锁。</p>
 */
@Component
public class ConversationLock {

    private final Set<Long> busy = ConcurrentHashMap.newKeySet();

    /** 尝试占用会话；已有任务在跑时返回 false。 */
    public boolean tryAcquire(Long conversationId) {
        return conversationId != null && busy.add(conversationId);
    }

    /** 释放会话占用，可重复调用。 */
    public void release(Long conversationId) {
        if (conversationId != null) {
            busy.remove(conversationId);
        }
    }
}
