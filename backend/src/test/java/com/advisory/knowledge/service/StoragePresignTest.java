package com.advisory.knowledge.service;

import com.advisory.common.config.MinioConfig;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signing an upload URL is a local computation, so the split between the internal and the public
 * MinIO client can be pinned without a container.
 *
 * <p>This is the deployment bug the test exists for: SigV4 signs the host, so a URL signed with the
 * compose-internal name {@code minio:9000} is rejected by MinIO when a browser sends it to
 * {@code localhost:9000}. The presign client must therefore be built on the public endpoint.</p>
 */
class StoragePresignTest {

    @Test
    void uploadUrlUsesThePublicEndpoint() {
        // The production wiring, not a hand-built client: the test must fail if MinioConfig stops
        // pinning the region (presigning would then call the public host before signing).
        MinioConfig config = new MinioConfig();
        MinioClient internal = config.minioClient("http://minio:9000", "key", "secret");
        MinioClient publicClient = config.minioPresignClient("http://minio:9000",
                "http://localhost:9000", "us-east-1", "key", "secret");

        StorageService storage = new StorageService(internal, publicClient, "advisory");

        String url = storage.presignPut("documents/1/sample.txt", Duration.ofMinutes(10));

        assertTrue(url.startsWith("http://localhost:9000/advisory/documents/1/sample.txt?"),
                "the signed host must be the browser-reachable one, was: " + url);
        assertTrue(url.contains("X-Amz-Signature="), "the URL must be presigned, was: " + url);
    }
}
