package com.findoc.messaging;

import com.findoc.service.document.IngestionService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaIngestionConsumerTest {
    private final IngestionService ingestionService = mock(IngestionService.class);
    private final KafkaIngestionConsumer consumer = new KafkaIngestionConsumer(ingestionService);

    @Test
    void delegatesSuccessfulJobToIngestionService() throws Exception {
        IngestionJob job = job(1);

        consumer.consume(job);

        verify(ingestionService).ingest(job);
    }

    @Test
    void propagatesIngestionFailureToKafkaErrorHandler() throws Exception {
        IngestionJob job = job(1);
        doThrow(new IllegalStateException("embedding unavailable"))
            .when(ingestionService).ingest(job);

        assertThatThrownBy(() -> consumer.consume(job))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("embedding unavailable");
    }

    private IngestionJob job(int attemptNumber) {
        return new IngestionJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), attemptNumber);
    }
}