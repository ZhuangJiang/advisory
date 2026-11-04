package com.advisory.chat;

import com.advisory.chat.model.ChatClientFactory;
import com.advisory.chat.model.SpringAiChatModelGateway;
import com.advisory.chat.orchestration.ChatModelGateway;
import com.advisory.chat.orchestration.ChatOrchestrator;
import com.advisory.knowledge.retrieval.RetrievalService;
import com.advisory.provider.service.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 作答链路的 bean 装配：模型客户端工厂、编排器，以及编排器需要的两个线程池。
 *
 * <p>只有 {@code app.chat.gateway} 未设置（或为 {@code real}）时才注册 Spring AI 网关；
 * {@code IntegrationTestBase} 把它设为 {@code stub}，集成测试于是改用
 * {@code com.advisory.support.TestChatModelGatewayConfig} 提供的确定性网关，完全不碰真实提供商。
 * 该配置项刻意不写进 {@code application.yml}：生产环境永远不该设置它。</p>
 *
 * <p>超时与 spec §11 一致（单步 60 秒、整体 180 秒），以毫秒为单位可配，测试才能把它改小而不必真的等。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ChatConfig {

    @Bean
    public ChatClientFactory chatClientFactory(JdbcTemplate jdbc, CryptoService cryptoService) {
        return new ChatClientFactory(jdbc, cryptoService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.chat", name = "gateway", havingValue = "real",
            matchIfMissing = true)
    /** 生产网关：仅在 app.chat.gateway 未设置或为 real 时注册。 */
    public ChatModelGateway chatModelGateway(ChatClientFactory clientFactory) {
        return new SpringAiChatModelGateway(clientFactory);
    }

    /** 装配编排器并传入生产网关。 */
    @Bean(destroyMethod = "shutdown")
    public ChatOrchestrator chatOrchestrator(RetrievalService retrievalService,
                                             ChatModelGateway gateway,
                                             ObjectMapper objectMapper,
                                             @Value("${app.chat.step-timeout-ms:60000}") long stepTimeoutMs,
                                             @Value("${app.chat.overall-timeout-ms:180000}") long overallTimeoutMs) {
        return new ChatOrchestrator(retrievalService, gateway, objectMapper,
                Duration.ofMillis(stepTimeoutMs), Duration.ofMillis(overallTimeoutMs));
    }

    /**
     * 执行编排任务的线程池。
     *
     * <p>刻意有界：单副本、服务少量内部用户，无界队列会让一波提问先把数据库连接池耗尽，
     * 而 409 忙等保护是「按会话」而非全局的，拦不住这种压力。</p>
     */
    /** 有界的编排线程池。 */
    @Bean(name = "chatWorkerExecutor", destroyMethod = "shutdownNow")
    public ExecutorService chatWorkerExecutor() {
        return Executors.newFixedThreadPool(4, namedDaemonThreads("chat-worker-"));
    }

    /** 单线程足够：它只负责每 15 秒往未关闭的 emitter 上写一次心跳。 */
    @Bean(name = "chatHeartbeatScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(namedDaemonThreads("chat-heartbeat-"));
    }

    private static ThreadFactory namedDaemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
