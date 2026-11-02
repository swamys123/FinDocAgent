package com.findoc.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.findoc.config.TraceContext;
import com.findoc.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Service
public class OutboxDelivery {
    private final OutboxRepository outboxRepository;
    private final IngestionProducer ingestionProducer;
    private final ObjectMapper objectMapper;
    private final TraceContext traceContext;

    public OutboxDelivery(OutboxRepository outboxRepository,
                          IngestionProducer ingestionProducer,
                          ObjectMapper objectMapper,
                          TraceContext traceContext) {
        this.outboxRepository = outboxRepository;
        this.ingestionProducer = ingestionProducer;
        this.objectMapper = objectMapper;
        this.traceContext = traceContext;
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

        traceContext.continueTrace(event.traceContext(), "findoc.ingestion.outbox.publish", () -> {
            ingestionProducer.publish(job);
            if (outboxRepository.markSent(event.id()) != 1) {
                throw new IllegalStateException("Unable to mark ingestion outbox event sent: " + event.id());
            }
            return null;
        });
        return true;
    }
}
