package com.advisory.knowledge.service;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin wrapper around the single MinIO bucket.
 *
 * <p>Uploads never pass through the application: clients PUT directly to a presigned URL. The
 * signature only covers host and query string, so callers must not add signed headers (for example
 * {@code Content-Type}) to the PUT. MinIO's maximum presign lifetime is seven days; a shorter TTL is
 * clamped to at least one second.</p>
 *
 * <p>SDK failures are rethrown as {@link IllegalStateException}: they are infrastructure problems
 * and surface as {@code INTERNAL_ERROR}, never as client validation errors. {@link #stat(String)} is
 * the one exception: a missing object is a normal answer ({@code false}).</p>
 */
@Service
public class StorageService {

    private static final int MAX_PRESIGN_SECONDS = 7 * 24 * 60 * 60;

    private final MinioClient minioClient;
    private final String bucket;

    public StorageService(MinioClient minioClient, @Value("${app.minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    public String presignPut(String objectKey, Duration ttl) {
        return presign(objectKey, ttl, Method.PUT);
    }

    public String presignGet(String objectKey, Duration ttl) {
        return presign(objectKey, ttl, Method.GET);
    }

    private String presign(String objectKey, Duration ttl, Method method) {
        int expirySeconds = (int) Math.min(Math.max(ttl.getSeconds(), 1), MAX_PRESIGN_SECONDS);
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expirySeconds)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to presign " + method + " for " + objectKey, e);
        }
    }

    public InputStream getObject(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read object " + objectKey, e);
        }
    }

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
            throw new IllegalStateException("Failed to write object " + objectKey, e);
        }
    }

    public void remove(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to remove object " + objectKey, e);
        }
    }

    /** Returns whether the object exists. A missing bucket also answers {@code false}. */
    public boolean stat(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            throw new IllegalStateException("Failed to stat object " + objectKey, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stat object " + objectKey, e);
        }
    }
}
