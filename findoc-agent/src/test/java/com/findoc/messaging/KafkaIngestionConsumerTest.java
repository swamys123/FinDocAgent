package com.findoc.messaging;

import com.findoc.service.document.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class KafkaIngestionConsumerTest {
    private static final String TOPIC = "findoc.ingestion";
    private static final String DLQ = "findoc.ingestion.dlq";

    private final IngestionService ingestionService = mock(IngestionService.class);
    private final KafkaTemplate<String, IngestionJob> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaIngestionConsumer consumer = new KafkaIngestionConsumer(
        ingestionService, kafkaTemplate, TOPIC, DLQ);

    @Test
    void republishesFailedJobUntilRetryLimit() throws Exception {
        IngestionJob job = job(1);
        doThrow(new IllegalStateException("embedding unavailable"))
            .when(ingestionService).ingest(job);
        allowKafkaSend();

        consumer.consume(job);

        verify(ingestionService).recordFailure(job, "embedding unavailable", false);
        verify(kafkaTemplate).send(eq(TOPIC), eq(job.documentId().toString()),
            eq(new IngestionJob(job.documentId(), job.tenantId(), job.userId(), 2)));
        verify(kafkaTemplate, never()).send(eq(DLQ), eq(job.documentId().toString()), eq(job));
    }

    @Test
    void sendsExhaustedJobToDlq() throws Exception {
        IngestionJob job = job(3);
        doThrow(new IllegalStateException("embedding unavailable"))
            .when(ingestionService).ingest(job);
        allowKafkaSend();

        consumer.consume(job);

        verify(ingestionService).recordFailure(job, "embedding unavailable", true);
        verify(kafkaTemplate).send(DLQ, job.documentId().toString(), job);
        verify(kafkaTemplate, never()).send(eq(TOPIC), eq(job.documentId().toString()),
            eq(new IngestionJob(job.documentId(), job.tenantId(), job.userId(), 4)));
    }

    private void allowKafkaSend() {
        org.mockito.Mockito.when(kafkaTemplate.send(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(IngestionJob.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    private IngestionJob job(int attemptNumber) {
        return new IngestionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), attemptNumber);
    }
}