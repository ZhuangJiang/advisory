package com.advisory.knowledge.service;

import com.advisory.common.config.MinioConfig;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签发上传 URL 是纯本地计算，因此内网客户端与公网客户端的分工不必起容器就能钉住。
 *
 * <p>这个用例正是为了那次部署事故而存在：SigV4 会把 host 一起签进去，于是用 compose 内网名
 * {@code minio:9000} 签出的 URL，一旦浏览器发到 {@code localhost:9000}，就会被 MinIO 拒绝。
 * 所以预签名客户端必须建立在公网端点上。</p>
 *
 * <p>另一条被钉住的是「一库一桶」：URL 路径里的第一段必须是该知识库自己的桶，
 * 否则两个知识库的同名文件仍有互相覆盖的可能，这正是把桶拆开的理由。</p>
 */
class StoragePresignTest {

    @Test
    void uploadUrlUsesThePublicEndpoint() {
        // 这里用的是生产装配而不是手工 new 出来的客户端：一旦 MinioConfig 不再固定 region，
        // 本用例就会失败（否则预签名会先去请求公网 host 再签名）。
        MinioConfig config = new MinioConfig();
        MinioClient internal = config.minioClient("http://minio:9000", "key", "secret");
        MinioClient publicClient = config.minioPresignClient("http://minio:9000",
                "http://localhost:9000", "us-east-1", "key", "secret");

        StorageService storage = new StorageService(internal, publicClient, "advisory");

        String url = storage.presignPut(7L, "kb/7/sample.txt", Duration.ofMinutes(10));

        assertTrue(url.startsWith("http://localhost:9000/advisory-kb-7/kb/7/sample.txt?"),
                "the signed host must be the browser-reachable one and the path must start with "
                        + "the knowledge base's own bucket, was: " + url);
        assertTrue(url.contains("X-Amz-Signature="), "the URL must be presigned, was: " + url);
    }

    @Test
    void everyKnowledgeBaseGetsItsOwnBucket() {
        MinioConfig config = new MinioConfig();
        MinioClient internal = config.minioClient("http://minio:9000", "key", "secret");
        MinioClient publicClient = config.minioPresignClient("http://minio:9000",
                "http://localhost:9000", "us-east-1", "key", "secret");
        StorageService storage = new StorageService(internal, publicClient, "advisory");

        assertEquals("advisory-kb-1", storage.bucketName(1));
        assertEquals("advisory-kb-2", storage.bucketName(2));
        assertNotEquals(storage.bucketName(1), storage.bucketName(2),
                "两个知识库必须落在不同的桶里，否则对象键相同就会互相覆盖");
    }
}
