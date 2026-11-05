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
 * MinIO 客户端与存储桶初始化。
 *
 * <p>两个客户端共用一套凭证：{@code minioClient} 走 compose 内网地址（{@code http://minio:9000}），
 * {@code minioPresignClient} 则用<em>浏览器</em>可达的地址（{@code http://localhost:9000}，生产环境为对外域名）
 * 生成预签名 URL。SigV4 签名覆盖了 host，为内网地址签出的 URL 在浏览器里必然签名不匹配，
 * 这个拆分正是容器化部署后上传能跑通的原因。预签名是纯本地计算（不发请求），因此公开客户端
 * 无需真正解析公网域名。</p>
 *
 * <p>业务对象一律写入每个知识库自己的桶（{@code <前缀>-kb-<id>}，见 {@code StorageService}）。
 * 这里仍然会按需创建那个<em>共享</em>桶，它只作为历史版本的迁移来源存在——升级前所有文档都放在
 * {@code kb/<id>/...} 前缀下，保留这个桶才能把老对象搬到新桶而不必回滚。MinIO 暂时不可达不能阻断
 * 应用启动（上下文测试并不保证有 MinIO），故桶检查失败只打警告，真正的错误留给第一次上传暴露。</p>
 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    /**
     * 服务端内部使用的客户端：负责建桶、写入对象、生成下载地址。
     *
     * @param endpoint  MinIO 内部地址
     * @param accessKey 访问密钥
     * @param secretKey 密钥
     * @return MinIO 客户端
     */
    @Bean
    @Primary
    public MinioClient minioClient(@Value("${app.minio.endpoint}") String endpoint,
                                   @Value("${app.minio.access-key}") String accessKey,
                                   @Value("${app.minio.secret-key}") String secretKey) {
        return build(endpoint, accessKey, secretKey);
    }

    /**
     * 专用于给浏览器签名的客户端；未配置公网地址时退回内部地址。
     *
     * <p>region 固定写入而不是查询获取：{@code MinioClient} 在签名前默认会向 endpoint 发
     * {@code GET /bucket?location=} 探询区域，而公网地址本就不是本 JVM 能解析的名字
     * （在 compose 里甚至可能解析到自己），固定 region 后预签名重新变回纯本地计算。</p>
     *
     * @param endpoint       内部地址，作为公网地址的兜底
     * @param publicEndpoint 浏览器可达的地址，可为空
     * @param region         区域，MinIO 实际只支持默认区域
     * @param accessKey      访问密钥
     * @param secretKey      密钥
     * @return 用于预签名的客户端
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

    /**
     * 启动时确保历史共享桶存在；失败只记警告，不阻断启动。
     *
     * <p>新对象不再写入这里，但升级前上传的文件仍在其中，迁移脚本需要一个稳定的源桶。
     * 知识库专属桶由 {@code KnowledgeBaseService#ensureBuckets} 在启动完成后补齐。</p>
     *
     * @param minioClient 内部客户端
     * @param bucket      共享桶名，作为知识库桶名的前缀
     * @return 启动回调
     */
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

    /**
     * 按内部地址构造客户端，不指定 region（写入操作允许 SDK 自行探询）。
     *
     * @param endpoint  地址
     * @param accessKey 访问密钥
     * @param secretKey 密钥
     * @return 新建的客户端
     */
    private static MinioClient build(String endpoint, String accessKey, String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
