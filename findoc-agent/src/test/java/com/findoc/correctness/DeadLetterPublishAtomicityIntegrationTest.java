package com.findoc.correctness;

import com.findoc.messaging.IngestionJob;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 3 (b2): the FAILED status is committed inside the DLQ destination resolver, before the DLQ send, with no
 * transaction or outbox tying the two together. The DLQ is made permanently unavailable here.
 */
@Import(SpyingKafkaTemplateConfiguration.class)
@TestPropertySource(properties = "spring.kafka.producer.properties.max.block.ms=3000")
class DeadLetterPublishAtomicityIntegrationTest extends CorrectnessTestSupport {
    static final String TOPIC = "correctness-dlq-atomic-" + UUID.randomUUID();
    static final String DLQ_TOPIC = TOPIC + ".dlq";

    @DynamicPropertySource
    static void registerTopics(DynamicPropertyRegistry registry) {
        registry.add("findoc.ingestion.topic", () -> TOPIC);
        registry.add("findoc.ingestion.dlq", () -> DLQ_TOPIC);
        registry.add("findoc.ingestion.group", () -> TOPIC + "-group");
    }

    @Autowired
    @SuppressWarnings("rawtypes")
    KafkaTemplate kafkaTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void documentIsNotMarkedFailedUntilTheDeadLetterPublishSucceeds() {
        AtomicInteger dlqAttempts = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            ProducerRecord<?, ?> record = invocation.getArgument(0);
            if (DLQ_TOPIC.equals(record.topic())) {
                dlqAttempts.incrementAndGet();
                throw new KafkaException("dlq unavailable");
            }
            return invocation.callRealMethod();
        }).when(kafkaTemplate).send(Mockito.any(ProducerRecord.class));
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenThrow(new IllegalStateException("embedding down"));

        StoredDocument stored = newPendingDocument(words("atomic" + UUID.randomUUID().toString().replace("-", ""), 50));
        ingestionProducer.publish(new IngestionJob(stored.id(), stored.account().tenant().getId(), stored.account().user().getId()));

        Awaitility.await().alias("consumer must attempt the DLQ publish")
            .atMost(Duration.ofSeconds(60)).until(() -> dlqAttempts.get() >= 1);

        assertThat(status(stored.id()))
            .as("the job is not on the DLQ, so the document must not already be terminal FAILED")
            .isNotEqualTo("FAILED");
    }
}
