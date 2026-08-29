package com.findoc.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_sources")
@Getter
public class DocumentSource {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "content", nullable = false, columnDefinition = "BYTEA")
    private byte[] content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentSource() {}

    public DocumentSource(Document document, Tenant tenant, byte[] content) {
        this.document = document;
        this.tenant = tenant;
        this.content = content;
        this.createdAt = Instant.now();
    }
}