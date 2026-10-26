package com.findoc.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.findoc.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Service
public class OutboxDelivery {
    private final OutboxRepository outboxRepository;
    private final IngestionProducer ingestionProducer;
    private final ObjectMapper objectMapper;

    public OutboxDelivery(OutboxRepository outboxRepository,
                          IngestionProducer ingestionProducer,
                          ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.ingestionProducer = ingestionProducer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean deliverNext() {
        var events = outboxRepository.lockNextPending();
        if (events.isEmpty()) {
            return false;
        }

        var event = events.get(0);
        IngestionJob job;
        try {
            job = objectMapper.readValue(event.payload(), IngestionJob.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to deserialize ingestion outbox event " + event.id(), exception);
        }

        ingestionProducer.publish(job);
        if (outboxRepository.markSent(event.id()) != 1) {
            throw new IllegalStateException("Unable to mark ingestion outbox event sent: " + event.id());
        }
        return true;
    }
}
