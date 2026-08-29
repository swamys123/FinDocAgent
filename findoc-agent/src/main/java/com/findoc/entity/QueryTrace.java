package com.findoc.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "query_traces")
@Getter
public class QueryTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AgentSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "query", nullable = false, columnDefinition = "TEXT")
    private String query;

    @Column(name = "intent", length = 50)
    private String intent;

    @Column(name = "steps", columnDefinition = "TEXT")
    private String steps;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QueryTrace() {
    }

    public QueryTrace(AgentSession session, Tenant tenant, String query, String intent, String steps, String answer, BigDecimal confidence, Integer durationMs) {
        this.session = session;
        this.tenant = tenant;
        this.query = query;
        this.intent = intent;
        this.steps = steps;
        this.answer = answer;
        this.confidence = confidence;
        this.durationMs = durationMs;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
