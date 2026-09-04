package com.findoc.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaIngestionProducerTest {
    private final KafkaTemplate<String, IngestionJob> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaIngestionProducer producer = new KafkaIngestionProducer(kafkaTemplate, "findoc.ingestion");

    @Test
    void waitsForSuccessfulPublication() {
        IngestionJob job = job();
        when(kafkaTemplate.send("findoc.ingestion", job.documentId().toString(), job))
            .thenReturn(CompletableFuture.completedFuture(null));

        producer.publish(job);

        verify(kafkaTemplate).send(eq("findoc.ingestion"), eq(job.documentId().toString()), eq(job));
    }

    @Test
    void propagatesPublicationFailure() {
        IngestionJob job = job();
        CompletableFuture<SendResult<String, IngestionJob>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send("findoc.ingestion", job.documentId().toString(), job))
            .thenReturn(failed);

        assertThatThrownBy(() -> producer.publish(job))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Unable to confirm ingestion publication");
    }

    private IngestionJob job() {
        return new IngestionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
