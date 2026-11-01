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

/**
 * MinIO client and bucket bootstrap.
 *
 * <p>One {@link MinioClient} is built from {@code app.minio.*}. At startup the bucket is created if
 * it is missing so a fresh deployment does not fail its first upload with
 * {@code NoSuchBucket}. MinIO being temporarily unreachable must not abort the application (the
 * context test runs without a guaranteed MinIO), so the bucket check only logs a warning; the first
 * upload attempt reports the real error.</p>
 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(@Value("${app.minio.endpoint}") String endpoint,
                                   @Value("${app.minio.access-key}") String accessKey,
                                   @Value("${app.minio.secret-key}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
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
}
