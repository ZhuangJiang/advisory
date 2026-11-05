package com.advisory.knowledge.service;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 单个 MinIO 存储桶的薄封装。
 *
 * <p>上传不经过应用：客户端直接 PUT 到预签名地址。该地址由基于<em>公网</em>端点的第二个客户端签发
 * （见 {@code MinioConfig}），因为 SigV4 签名覆盖了 host——为 {@code minio:9000} 签出的地址，浏览器用不了。
 * 签名同样覆盖查询串，所以调用方不能给 PUT 追加任何参与签名的头（例如 {@code Content-Type}）。
 * MinIO 预签名最长七天，传入更短的 TTL 会被夹到至少一秒。</p>
 *
 * <p>SDK 失败统一包成 {@link IllegalStateException}：这些属于基础设施问题，最终表现为
 * {@code INTERNAL_ERROR}，绝不能被当成客户端的参数错误。{@link #stat(String)} 是唯一例外——
 * 对象不存在是一个正常答案（{@code Optional.empty()}）。</p>
 */
@Service
public class StorageService {

    private static final int MAX_PRESIGN_SECONDS = 7 * 24 * 60 * 60;

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final String bucket;

    public StorageService(MinioClient minioClient,
                          @Qualifier("minioPresignClient") MinioClient presignClient,
                          @Value("${app.minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.presignClient = presignClient;
        this.bucket = bucket;
    }

    /** 生成上传用的预签名地址，由公网客户端签名。 */
    public String presignPut(String objectKey, Duration ttl) {
        return presign(objectKey, ttl, Method.PUT);
    }

    /** 生成下载用的预签名地址。 */
    public String presignGet(String objectKey, Duration ttl) {
        return presign(objectKey, ttl, Method.GET);
    }

    private String presign(String objectKey, Duration ttl, Method method) {
        int expirySeconds = (int) Math.min(Math.max(ttl.getSeconds(), 1), MAX_PRESIGN_SECONDS);
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expirySeconds)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("为 " + objectKey + " 生成 " + method + " 预签名地址失败", e);
        }
    }

    /** 读取对象字节流，调用方负责关闭。 */
    public InputStream getObject(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("读取对象 " + objectKey + " 失败", e);
        }
    }

    /** 写入文本对象，用于保存解析产物。 */
    public void putText(String objectKey, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("text/plain; charset=utf-8")
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("写入对象 " + objectKey + " 失败", e);
        }
    }

    /** 删除对象；对象本就不存在时视为成功。 */
    public void remove(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("删除对象 " + objectKey + " 失败", e);
        }
    }

    /**
     * 查询对象并返回其存储大小。对象（或桶）不存在属于正常答案（{@code Optional.empty()}），
     * 真正的基础设施故障照常抛出。
     *
     * @param objectKey 对象键
     * @return 对象元数据，不存在时为空
     */
    public Optional<ObjectStat> stat(String objectKey) {
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return Optional.of(new ObjectStat(response.size()));
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return Optional.empty();
            }
            throw new IllegalStateException("查询对象 " + objectKey + " 元信息失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("查询对象 " + objectKey + " 元信息失败", e);
        }
    }

    /** 上传流程所需的已存对象元数据。 */
    public record ObjectStat(long size) {
    }
}
