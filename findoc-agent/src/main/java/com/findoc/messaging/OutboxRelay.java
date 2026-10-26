package com.findoc.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxDelivery outboxDelivery;
    private final int batchSize;

    public OutboxRelay(OutboxDelivery outboxDelivery,
                       @Value("${findoc.ingestion.outbox.batch-size:20}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Outbox relay batch size must be positive");
        }
        this.outboxDelivery = outboxDelivery;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${findoc.ingestion.outbox.poll-interval-ms:1000}")
    public void relay() {
        for (int delivered = 0; delivered < batchSize; delivered++) {
            try {
                if (!outboxDelivery.deliverNext()) {
                    return;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Outbox relay could not publish the next pending event; it will be retried", exception);
                return;
            }
        }
    }
}
