package com.findoc.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_chunks")
@Getter
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "token_count")
    @Setter
    private Integer tokenCount;

    @Column(name = "embedding")
    @Setter
    private byte[] embedding;

    @Column(name = "metadata", columnDefinition = "TEXT")
    @Setter
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentChunk() {
    }

    public DocumentChunk(Document document, Tenant tenant, Integer chunkIndex, String content) {
        this.document = document;
        this.tenant = tenant;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.createdAt = Instant.now();
    }

}
