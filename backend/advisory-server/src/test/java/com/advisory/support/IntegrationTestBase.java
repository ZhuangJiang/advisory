package com.advisory.support;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * 需要真实 PostgreSQL（带 pgvector/ltree/pg_trgm）与 MinIO 的集成用例基类。
 *
 * <p>容器在静态初始化块里每个 JVM 只启动一次，并一直存活到整轮测试结束：Spring 应用上下文
 * （以及由 {@link DynamicPropertySource} 解析出的数据源 URL）会跨测试类缓存，
 * 若在类之间销毁容器，缓存的数据源就会指向一个已经死掉的端口。容器的最终回收交给
 * Testcontainers 的 Ryuk，在 JVM 退出时统一清理。数据库由 Flyway 在上下文启动时迁移；
 * {@code advisory} 桶在每个测试类之前重新创建，以保证预签名 URL 可用。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    protected static final String MINIO_ACCESS_KEY = "minioadmin";
    protected static final String MINIO_SECRET_KEY = "minioadmin";
    protected static final String MINIO_BUCKET = "advisory";

    private static final int MINIO_PORT = 9000;

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                    .withDatabaseName("advisory")
                    .withUsername("advisory")
                    .withPassword("advisory");

    protected static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio"))
                    .withExposedPorts(MINIO_PORT)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server /data")
                    .waitingFor(Wait.forHttp("/minio/health/live")
                            .forPort(MINIO_PORT)
                            .withStartupTimeout(Duration.ofMinutes(2)));

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.minio.endpoint", IntegrationTestBase::minioEndpoint);
        registry.add("app.minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("app.minio.secret-key", () -> MINIO_SECRET_KEY);
        registry.add("app.minio.bucket", () -> MINIO_BUCKET);
        // 选用确定性的桩 EmbeddingGateway（TestEmbeddingGatewayConfig）而非真实的 Spring AI 网关，
        // 确保任何集成用例都不会真的向提供商发网络请求。
        registry.add("app.embedding.gateway", () -> "stub");
        // 对话链路同理：由 TestChatModelGatewayConfig 提供一个按脚本应答的 ChatModelGateway。
        registry.add("app.chat.gateway", () -> "stub");
    }

    @BeforeAll
    static void createBucket() {
        ensureBucket();
    }

    protected static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(MINIO_PORT);
    }

    protected static MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioEndpoint())
                .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                .build();
    }

    protected static void ensureBucket() {
        try {
            MinioClient client = minioClient();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(MINIO_BUCKET).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(MINIO_BUCKET).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure MinIO bucket '" + MINIO_BUCKET + "'", e);
        }
    }
}
