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
 * Beans of the answer path: the model client factory, the orchestrator and the two thread pools it
 * needs.
 *
 * <p>The Spring AI gateway is only registered when {@code app.chat.gateway} is unset (or
 * {@code real}); {@code IntegrationTestBase} sets it to {@code stub} so integration tests get the
 * deterministic gateway from {@code com.advisory.support.TestChatModelGatewayConfig} instead and
 * never touch a provider. The property is deliberately absent from {@code application.yml}: in
 * production it must never be set.</p>
 *
 * <p>Timeouts mirror spec §11 (60s per step, 180s per run) and are configurable in milliseconds so a
 * test can shrink them without waiting.</p>
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
    public ChatModelGateway chatModelGateway(ChatClientFactory clientFactory) {
        return new SpringAiChatModelGateway(clientFactory);
    }

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
     * Runs the orchestrations. Bounded on purpose: one replica serving a handful of internal users,
     * and an unbounded pool would let a burst of questions exhaust the connection pool before the
     * 409 guard ever applies (it is per conversation, not global).
     */
    @Bean(name = "chatWorkerExecutor", destroyMethod = "shutdownNow")
    public ExecutorService chatWorkerExecutor() {
        return Executors.newFixedThreadPool(4, namedDaemonThreads("chat-worker-"));
    }

    /** One thread is enough: it only writes 15s pings to the open emitters. */
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
