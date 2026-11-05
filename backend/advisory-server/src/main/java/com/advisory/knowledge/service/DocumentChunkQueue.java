package com.advisory.knowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文档裁切队列：单线程、先进先出、同一时刻只跑一个文档。
 *
 * <p>解析、切片和向量化三步里最贵的是向量化：它要按批把整篇文档发给 embedding 服务，并在结果回来前
 * 一直占着连接。如果同时放开几个文档去跑，一次上传几十个文件就会同时打出几十条 embedding 请求，
 * 提供商的限流一触发，整批文档一起失败——这比慢一点更糟。因此这里刻意把并发压到 1：提交顺序即执行
 * 顺序，排在后面的文档只需等待，不会互相挤占。</p>
 *
 * <p>队列只是「执行器 + 待跑列表」：执行器是容量为 1 的 {@link ThreadPoolExecutor}，待跑列表是插入
 * 有序的 {@code ticket -> documentId} 映射。为什么不用执行器自己的队列算位次：{@code BlockingQueue}
 * 只能拿到队首，算不出某个文档排第几，而界面要显示「排队中（第 3 位）」。ticket 让同一次提交
 * 能被精确摘除——同一个文档允许重复入队（由状态 CAS 决定谁真正干活），只按文档 id 摘会摘错那一条。</p>
 *
 * <p>容量是有界的：队列满时 {@link #submit(long, Runnable)} 返回 {@code false}，由调用方把文档置为
 * {@code FAILED} 并给出可重试的文案。无声丢弃会让文档永远停在 {@code PENDING}，直到下次重启才被
 * 恢复逻辑捞回来。</p>
 */
public class DocumentChunkQueue {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkQueue.class);

    /** 队列容量上限，防止一次导入几千个文件把内存撑爆。 */
    static final int DEFAULT_CAPACITY = 500;

    private final ThreadPoolExecutor executor;
    private final ReentrantLock lock = new ReentrantLock();
    /** 已提交、尚未开始执行的条目；{@code LinkedHashMap} 的插入顺序就是执行顺序。 */
    private final Map<Long, Long> waiting = new LinkedHashMap<>();
    private final AtomicLong nextTicket = new AtomicLong();
    private final int capacity;

    /** 使用默认容量创建队列。 */
    public DocumentChunkQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity 排队上限（不含正在执行的那一个），必须为正数
     */
    public DocumentChunkQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("裁切队列容量必须为正数");
        }
        this.capacity = capacity;
        this.executor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "doc-chunk-queue");
                    // 守护线程：进程退出时不需要等一个可能持续几分钟的 embedding 请求跑完；
                    // 留在中间状态的文档由启动恢复逻辑重新入队。
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 把任务排到队尾。
     *
     * @param documentId 文档主键，仅用于计算排队位次
     * @param task       任务体，在本队列唯一的线程上执行
     * @return 已入队返回 {@code true}；队列已满或已关闭返回 {@code false}，此时任务不会执行
     */
    public boolean submit(long documentId, Runnable task) {
        long ticket = nextTicket.incrementAndGet();
        lock.lock();
        try {
            if (waiting.size() >= capacity) {
                return false;
            }
            waiting.put(ticket, documentId);
        } finally {
            lock.unlock();
        }
        try {
            executor.execute(() -> {
                markStarted(ticket);
                task.run();
            });
            return true;
        } catch (RejectedExecutionException e) {
            removeSilently(ticket);
            log.warn("裁切队列拒绝接收文档 {}（容量 {}）", documentId, capacity);
            return false;
        }
    }

    /**
     * 计算排队位次（从 1 开始）。
     *
     * <p>正在执行的那个文档不在待跑列表里，因此返回 {@code 0}——它已经在跑，界面该显示阶段而不是位次。
     * 同一文档重复入队时返回它最早的那一次，界面展示的是「还要等几个」的最乐观值。</p>
     *
     * @param documentId 文档主键
     * @return 位次；不在排队中返回 {@code 0}
     */
    public int positionOf(long documentId) {
        lock.lock();
        try {
            int position = 0;
            Iterator<Map.Entry<Long, Long>> iterator = waiting.entrySet().iterator();
            while (iterator.hasNext()) {
                position++;
                if (iterator.next().getValue() == documentId) {
                    return position;
                }
            }
            return 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return 当前排队中的文档主键，按执行顺序排列（供排查与测试使用）
     */
    public List<Long> waitingSnapshot() {
        lock.lock();
        try {
            return new ArrayList<>(waiting.values());
        } finally {
            lock.unlock();
        }
    }

    /** @return 排队中的任务数（不含正在执行的那一个） */
    public int waitingCount() {
        lock.lock();
        try {
            return waiting.size();
        } finally {
            lock.unlock();
        }
    }

    /** @return 队列容量 */
    public int capacity() {
        return capacity;
    }

    /**
     * 停止接收新任务并中断正在执行的那一个。
     *
     * <p>用 {@code shutdownNow} 而不是 {@code shutdown}：一个正在等 embedding 响应的文档可能还要几十秒，
     * 没必要为了它把进程退出拖住。被中断的文档会停在中间状态，下次启动时由恢复逻辑重新入队。</p>
     */
    public void shutdown() {
        executor.shutdownNow();
        lock.lock();
        try {
            waiting.clear();
        } finally {
            lock.unlock();
        }
    }

    private void markStarted(long ticket) {
        removeSilently(ticket);
    }

    private void removeSilently(long ticket) {
        lock.lock();
        try {
            waiting.remove(ticket);
        } finally {
            lock.unlock();
        }
    }
}
