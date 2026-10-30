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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Base class for integration tests that need the real PostgreSQL (with pgvector/ltree/pg_trgm)
 * and MinIO.
 *
 * <p>Every test class extending this base boots the full application context against throwaway
 * containers. Flyway migrates the database on context startup; the {@code advisory} bucket is
 * created before the test class runs so presigned URLs work. Suppliers registered through
 * {@link DynamicPropertySource} override the {@code DB_*} / {@code MINIO_*} defaults from
 * {@code application.yml}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class IntegrationTestBase {

    protected static final String MINIO_ACCESS_KEY = "minioadmin";
    protected static final String MINIO_SECRET_KEY = "minioadmin";
    protected static final String MINIO_BUCKET = "advisory";

    private static final int MINIO_PORT = 9000;

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                    .withDatabaseName("advisory")
                    .withUsername("advisory")
                    .withPassword("advisory");

    @Container
    protected static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio"))
                    .withExposedPorts(MINIO_PORT)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server /data")
                    .waitingFor(Wait.forHttp("/minio/health/live")
                            .forPort(MINIO_PORT)
                            .withStartupTimeout(Duration.ofMinutes(2)));

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
