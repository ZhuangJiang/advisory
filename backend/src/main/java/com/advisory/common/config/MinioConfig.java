package com.advisory.common.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * MinIO clients and bucket bootstrap.
 *
 * <p>Two clients, one credential: {@code minioClient} talks to MinIO inside the compose network
 * ({@code http://minio:9000}), while {@code minioPresignClient} signs URLs with the address a
 * <em>browser</em> can reach ({@code http://localhost:9000}, or a public name in a real deployment).
 * A SigV4 signature covers the host, so a URL signed for the internal name is unusable by the client
 * that has to PUT the file — this split is what makes uploads work behind docker. Presigning is a
 * local computation (no request), so the public client never needs to resolve the public name.</p>
 *
 * <p>At startup the bucket is created if it is missing, so a fresh deployment does not fail its
 * first upload with {@code NoSuchBucket}. MinIO being temporarily unreachable must not abort the
 * application (the context test runs without a guaranteed MinIO), so the bucket check only logs a
 * warning; the first upload attempt reports the real error.</p>
 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    @Primary
    public MinioClient minioClient(@Value("${app.minio.endpoint}") String endpoint,
                                   @Value("${app.minio.access-key}") String accessKey,
                                   @Value("${app.minio.secret-key}") String secretKey) {
        return build(endpoint, accessKey, secretKey);
    }

    /**
     * Signs URLs for the browser. Defaults to the internal endpoint when no public one is set.
     *
     * <p>The region is pinned instead of looked up: {@code MinioClient} otherwise asks the endpoint
     * {@code GET /bucket?location=} before it can sign, and the public endpoint is by definition not
     * a name this JVM can reach (inside compose it may even resolve to itself). Pinning makes
     * presigning a local computation again.</p>
     */
    @Bean
    public MinioClient minioPresignClient(@Value("${app.minio.endpoint}") String endpoint,
                                          @Value("${app.minio.public-endpoint:}") String publicEndpoint,
                                          @Value("${app.minio.region:us-east-1}") String region,
                                          @Value("${app.minio.access-key}") String accessKey,
                                          @Value("${app.minio.secret-key}") String secretKey) {
        String target = publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        if (!target.equals(endpoint)) {
            log.info("Presigned upload URLs will use {} (internal endpoint is {})", target, endpoint);
        }
        return MinioClient.builder()
                .endpoint(target)
                .region(region)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public ApplicationRunner minioBucketInitializer(MinioClient minioClient,
                                                    @Value("${app.minio.bucket}") String bucket) {
        return args -> {
            try {
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Created MinIO bucket '{}'", bucket);
                }
            } catch (Exception e) {
                log.warn("MinIO bucket '{}' is not reachable at startup ({}); "
                        + "uploads will fail until MinIO is available", bucket, e.toString());
            }
        };
    }

    private static MinioClient build(String endpoint, String accessKey, String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
