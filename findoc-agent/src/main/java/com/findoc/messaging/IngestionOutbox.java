package com.findoc.messaging;

import com.findoc.config.TraceContext;
import com.findoc.repository.OutboxRepository;
import org.springframework.stereotype.Service;

@Service
public class IngestionOutbox {
    private final OutboxRepository outboxRepository;
    private final TraceContext traceContext;

    public IngestionOutbox(OutboxRepository outboxRepository, TraceContext traceContext) {
        this.outboxRepository = outboxRepository;
        this.traceContext = traceContext;
    }

    public void enqueue(IngestionJob job) {
        outboxRepository.enqueue(job.tenantId(), job, traceContext.capture());
    }
}
