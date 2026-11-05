package com.advisory.knowledge.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 知识库专属存储桶的薄封装：<b>一个知识库一个桶</b>，桶名为 {@code <前缀>-kb-<id>}。
 *
 * <p>之所以不共用一个大桶：对象键里的知识库前缀只是命名约定，任何一处漏拼或调用方自带键，就可能让两个
 * 知识库写到同一个键上；桶是存储层的硬边界，删库、配额、权限都能按桶处理。代价是每个知识库各自需要一次
 * 建桶，因此 {@link #createBucket} 被设计成幂等操作，既在新建知识库时调用，也在启动时对既有知识库补建。</p>
 *
 * <p>上传不经过应用：客户端直接 PUT 到预签名地址。该地址由基于<em>公网</em>端点的第二个客户端签发
 * （见 {@code MinioConfig}），因为 SigV4 签名覆盖了 host——为 {@code minio:9000} 签出的地址，浏览器用不了。
 * 签名同样覆盖查询串，所以调用方不能给 PUT 追加任何参与签名的头（例如 {@code Content-Type}）。
 * MinIO 预签名最长七天，传入更短的 TTL 会被夹到至少一秒。</p>
 *
 * <p>SDK 失败统一包成 {@link IllegalStateException}：这些属于基础设施问题，最终表现为
 * {@code INTERNAL_ERROR}，绝不能被当成客户端的参数错误。{@link #stat} 是唯一例外——
 * 对象不存在是一个正常答案（{@code Optional.empty()}）。</p>
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private static final int MAX_PRESIGN_SECONDS = 7 * 24 * 60 * 60;

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final String bucketPrefix;

    public StorageService(MinioClient minioClient,
                          @Qualifier("minioPresignClient") MinioClient presignClient,
                          @Value("${app.minio.bucket}") String bucketPrefix) {
        this.minioClient = minioClient;
        this.presignClient = presignClient;
        this.bucketPrefix = bucketPrefix;
    }

    /**
     * 知识库的桶名，形如 {@code advisory-kb-12}。
     *
     * <p>MinIO 的桶名只允许小写字母、数字与连字符，长度 3–63，不能以下划线或连字符开头结尾；
     * 因此前缀与 {@code kb-<id>} 都用连字符拼接，且前缀本身由部署配置保证合法。</p>
     *
     * @param kbId 知识库主键
     * @return 桶名
     */
    public String bucketName(long kbId) {
        return bucketPrefix + "-kb-" + kbId;
    }

    /**
     * 幂等地创建知识库的桶；已存在时直接返回。
     *
     * <p>失败抛出而不是吞掉：建库这个动作的语义包含「存储已经准备好」，若这里只记日志，
     * 用户会在下一次上传时才遇到 {@code NoSuchBucket}，且无从判断该找谁。</p>
     *
     * @param kbId 知识库主键
     */
    public void createBucket(long kbId) {
        String bucket = bucketName(kbId);
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket '{}' for knowledge base {}", bucket, kbId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("为知识库 " + kbId + " 创建存储桶 " + bucket + " 失败", e);
        }
    }

    /**
     * 尽力清空并删除知识库的桶；任何失败只记警告，不影响已提交的数据库删除。
     *
     * <p>删库是数据库层面的事实，桶只是它的投影。若 MinIO 暂时不可用就回滚数据库删除，用户会陷入
     * 「删不掉也改不了」的状态；留下一个空桶的代价小得多，且可以事后清理。</p>
     *
     * @param kbId 知识库主键
     */
    public void deleteBucketQuietly(long kbId) {
        String bucket = bucketName(kbId);
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                return;
            }
            List<DeleteObject> targets = new ArrayList<>();
            for (Result<Item> result : minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                targets.add(new DeleteObject(bucket, result.get().objectName()));
            }
            if (!targets.isEmpty()) {
                // removeObjects 是惰性的：不迭代返回结果，删除请求根本不会发出。
                for (Result<DeleteError> ignored : minioClient.removeObjects(
                        RemoveObjectsArgs.builder().bucket(bucket).objects(targets).build())) {
                    // 单个对象删除失败不阻断其余对象，最终由 minio 的日志体现。
                }
            }
            minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            log.info("Removed MinIO bucket '{}' of knowledge base {}", bucket, kbId);
        } catch (Exception e) {
            log.warn("Could not remove MinIO bucket '{}' of knowledge base {}: {}",
                    bucket, kbId, e.toString());
        }
    }

    /**
     * 生成上传用的预签名地址，由公网客户端签名。
     *
     * @param kbId      知识库主键，决定目标桶
     * @param objectKey 对象键
     * @param ttl       有效期
     * @return 预签名 PUT 地址
     */
    public String presignPut(long kbId, String objectKey, Duration ttl) {
        return presign(kbId, objectKey, ttl, Method.PUT);
    }

    /**
     * 生成下载用的预签名地址。
     *
     * @param kbId      知识库主键，决定目标桶
     * @param objectKey 对象键
     * @param ttl       有效期
     * @return 预签名 GET 地址
     */
    public String presignGet(long kbId, String objectKey, Duration ttl) {
        return presign(kbId, objectKey, ttl, Method.GET);
    }

    private String presign(long kbId, String objectKey, Duration ttl, Method method) {
        int expirySeconds = (int) Math.min(Math.max(ttl.getSeconds(), 1), MAX_PRESIGN_SECONDS);
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(bucketName(kbId))
                    .object(objectKey)
                    .expiry(expirySeconds)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "为知识库 " + kbId + " 的对象 " + objectKey + " 生成 " + method + " 预签名地址失败", e);
        }
    }

    /**
     * 读取对象字节流，调用方负责关闭。
     *
     * @param kbId      知识库主键
     * @param objectKey 对象键
     * @return 对象流
     */
    public InputStream getObject(long kbId, String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName(kbId))
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("读取知识库 " + kbId + " 的对象 " + objectKey + " 失败", e);
        }
    }

    /**
     * 写入文本对象，用于保存解析产物。
     *
     * @param kbId      知识库主键
     * @param objectKey 对象键
     * @param text      文本内容
     */
    public void putText(long kbId, String objectKey, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName(kbId))
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("text/plain; charset=utf-8")
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("写入知识库 " + kbId + " 的对象 " + objectKey + " 失败", e);
        }
    }

    /**
     * 删除对象；对象本就不存在时视为成功。
     *
     * @param kbId      知识库主键
     * @param objectKey 对象键
     */
    public void remove(long kbId, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName(kbId))
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("删除知识库 " + kbId + " 的对象 " + objectKey + " 失败", e);
        }
    }

    /**
     * 查询对象并返回其存储大小。对象（或桶）不存在属于正常答案（{@code Optional.empty()}），
     * 真正的基础设施故障照常抛出。
     *
     * @param kbId      知识库主键
     * @param objectKey 对象键
     * @return 对象元数据，不存在时为空
     */
    public Optional<ObjectStat> stat(long kbId, String objectKey) {
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName(kbId))
                    .object(objectKey)
                    .build());
            return Optional.of(new ObjectStat(response.size()));
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return Optional.empty();
            }
            throw new IllegalStateException("查询知识库 " + kbId + " 的对象 " + objectKey + " 元信息失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("查询知识库 " + kbId + " 的对象 " + objectKey + " 元信息失败", e);
        }
    }

    /** 上传流程所需的已存对象元数据。 */
    public record ObjectStat(long size) {
    }
}
