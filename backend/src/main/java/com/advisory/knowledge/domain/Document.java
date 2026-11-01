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
 * A stored document row (table {@code documents}).
 *
 * <p>Column definitions must match {@code V1__init.sql} because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate}. {@code status} is mapped as a
 * {@code String} on purpose: Hibernate 6 would otherwise map the {@link DocumentStatus} enum to a
 * PostgreSQL native enum type, while the frozen schema declares a {@code varchar(16)} with a CHECK
 * constraint. The enum is used by the service/worker layer and converted with {@code name()}.</p>
 *
 * <p>Status writes happen through compare-and-set {@code UPDATE} statements (see
 * {@code DocumentIngestWorker}); this entity is used for inserts and for responses built from the
 * row that was just written.</p>
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
        // for JPA
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
