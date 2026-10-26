package com.findoc.messaging;

import com.findoc.repository.OutboxRepository;
import org.springframework.stereotype.Service;

@Service
public class IngestionOutbox {
    private final OutboxRepository outboxRepository;

    public IngestionOutbox(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void enqueue(IngestionJob job) {
        outboxRepository.enqueue(job.tenantId(), job);
    }
}
