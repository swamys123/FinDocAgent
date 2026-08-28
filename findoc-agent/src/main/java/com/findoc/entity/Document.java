package com.findoc.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class Document {

    public enum Status {
        PENDING,
        PROCESSING,
        READY,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "filename", nullable = false, length = 500)
    private String filename;

    @Column(name = "file_type", nullable = false, length = 50)
    private String fileType;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private Status status = Status.PENDING;

    @Column(name = "error_msg")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Document() {
    }

    public Document(Tenant tenant, User user, String filename, String fileType) {
        this.tenant = tenant;
        this.user = user;
        this.filename = filename;
        this.fileType = fileType;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public User getUser() {
        return user;
    }

    public String getFilename() {
        return filename;
    }

    public String getFileType() {
        return fileType;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public Status getStatus() {
        return status;
    }

    public void markProcessing() {
        requireStatus(Status.PENDING);
        status = Status.PROCESSING;
        errorMessage = null;
    }

    public void markReady() {
        requireStatus(Status.PROCESSING);
        status = Status.READY;
        errorMessage = null;
    }

    public void markFailed(String message) {
        if (status != Status.PROCESSING && status != Status.PENDING) {
            throw new IllegalStateException("Document cannot fail from status " + status);
        }
        status = Status.FAILED;
        errorMessage = message;
        retryCount++;
    }

    public void resetForRetry() {
        requireStatus(Status.FAILED);
        status = Status.PENDING;
    }

    public void recordFailure(String message, boolean exhausted) {
        if (status != Status.PENDING && status != Status.PROCESSING) {
            throw new IllegalStateException("Document cannot fail from status " + status);
        }
        retryCount++;
        errorMessage = message;
        if (exhausted) {
            status = Status.FAILED;
        } else {
            status = Status.PENDING;
        }
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("Document must be " + expected + " but was " + status);
        }
    }
}
