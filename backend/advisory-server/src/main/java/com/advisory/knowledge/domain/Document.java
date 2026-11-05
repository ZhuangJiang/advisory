package com.advisory.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 文档行（表 {@code documents}）。
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto} 为 {@code validate}，列定义必须与 {@code V1__init.sql} 一致。
 * {@code status} 有意映射成 {@code String}：否则 Hibernate 6 会把 {@link DocumentStatus} 映射为 PostgreSQL
 * 的原生枚举类型，而这份已冻结的表结构声明的是带 CHECK 约束的 {@code varchar(16)}，启动校验会直接失败。
 * 枚举只在 service/worker 层使用，落库时用 {@code name()} 转换。</p>
 *
 * <p>状态变更走比较并设置的 {@code UPDATE} 语句（见 {@code DocumentIngestWorker}）；这个实体只用于插入，
 * 以及基于刚写入的那一行构造响应。</p>
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    @Column(name = "parsed_key", length = 1024)
    private String parsedKey;

    @Column(name = "mime", length = 255)
    private String mime;

    @Column(name = "size", nullable = false)
    private Long size = 0L;

    @Column(name = "checksum", length = 128)
    private String checksum;

    @Column(name = "status", nullable = false, length = 16)
    private String status = DocumentStatus.PENDING.name();

    @Column(name = "error_msg", columnDefinition = "text")
    private String errorMsg;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
        // 供 JPA 使用
    }

    public Document(Long kbId, Long nodeId, String filename, String objectKey) {
        this.kbId = kbId;
        this.nodeId = nodeId;
        this.filename = filename;
        this.objectKey = objectKey;
    }

    public Long getId() {
        return id;
    }

    public Long getKbId() {
        return kbId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public String getFilename() {
        return filename;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getParsedKey() {
        return parsedKey;
    }

    public void setParsedKey(String parsedKey) {
        this.parsedKey = parsedKey;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
