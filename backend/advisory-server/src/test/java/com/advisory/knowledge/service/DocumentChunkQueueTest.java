package com.advisory.knowledge.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 裁切队列的单元测试：不启动 Spring，只用真实线程验证三件容易退化的事——串行、先进先出、有界。
 *
 * <p>这三条正是「批量上传不至于拥挤」的全部含义：并发一旦被放开，embedding 服务会同时收到几十条请求；
 * 顺序一旦丢了，用户看到的排队位次就是假的；容量一旦无界，一次导几千个文件就能把内存吃满。</p>
 */
class DocumentChunkQueueTest {

    private DocumentChunkQueue queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    @Test
    void runsOneTaskAtATimeInSubmissionOrder() throws Exception {
        queue = new DocumentChunkQueue(10);
        List<Long> finished = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(5);

        for (long documentId = 1; documentId <= 5; documentId++) {
            long id = documentId;
            assertTrue(queue.submit(id, () -> {
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                sleepQuietly(5);
                finished.add(id);
                active.decrementAndGet();
                done.countDown();
            }));
        }

        assertTrue(done.await(10, TimeUnit.SECONDS), "排队的任务都必须被执行");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), new ArrayList<>(finished), "必须严格按提交顺序执行");
        assertEquals(1, peak.get(), "同一时刻只允许一个文档在跑");
        assertEquals(0, queue.waitingCount(), "全部跑完后队列必须为空");
    }

    @Test
    void reportsPositionOfWaitingDocuments() throws Exception {
        queue = new DocumentChunkQueue(10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        queue.submit(1, () -> {
            started.countDown();
            awaitQuietly(release);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "第一个任务必须马上开始执行");

        queue.submit(2, done::countDown);
        queue.submit(3, done::countDown);

        assertEquals(0, queue.positionOf(1), "正在执行的那个不在排队列表里");
        assertEquals(1, queue.positionOf(2));
        assertEquals(2, queue.positionOf(3));
        assertEquals(0, queue.positionOf(99), "不在队列里的文档返回 0");

        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, queue.positionOf(2), "跑完的文档不再占位次");
    }

    @Test
    void rejectsInsteadOfGrowingWithoutBound() throws Exception {
        queue = new DocumentChunkQueue(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        queue.submit(1, () -> {
            started.countDown();
            awaitQuietly(release);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(queue.submit(2, () -> { }), "容量是 1，第二篇应当能排进去");
        assertFalse(queue.submit(3, () -> { }), "超出容量必须被拒绝，交给调用方把文档置为失败");
        assertEquals(List.of(2L), queue.waitingSnapshot());

        release.countDown();
    }

    @Test
    void defaultCapacityMatchesTheDocumentedCeiling() {
        queue = new DocumentChunkQueue();
        assertEquals(500, queue.capacity());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
