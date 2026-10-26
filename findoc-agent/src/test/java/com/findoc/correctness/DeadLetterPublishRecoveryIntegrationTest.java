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
 * Audit 3 (b3/b4): the first DLQ publish fails transiently. The redelivered record then hits recordFailure() on a
 * document that is already FAILED, which throws, so the job can never be dead-lettered.
 */
@Import(SpyingKafkaTemplateConfiguration.class)
@TestPropertySource(properties = "spring.kafka.producer.properties.max.block.ms=3000")
class DeadLetterPublishRecoveryIntegrationTest extends CorrectnessTestSupport {
    static final String TOPIC = "correctness-dlq-recovery-" + UUID.randomUUID();
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
    void jobIsDeadLetteredEvenIfTheFirstDeadLetterPublishFails() {
        AtomicInteger dlqAttempts = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            ProducerRecord<?, ?> record = invocation.getArgument(0);
            if (DLQ_TOPIC.equals(record.topic()) && dlqAttempts.getAndIncrement() == 0) {
                throw new KafkaException("transient dlq failure");
            }
            return invocation.callRealMethod();
        }).when(kafkaTemplate).send(Mockito.any(ProducerRecord.class));
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenThrow(new IllegalStateException("embedding down"));

        StoredDocument stored = newPendingDocument(words("recover" + UUID.randomUUID().toString().replace("-", ""), 50));
        ingestionProducer.publish(new IngestionJob(stored.id(), stored.account().tenant().getId(), stored.account().user().getId()));

        Awaitility.await()
            .alias("after a transient DLQ failure the redelivered job must still reach the DLQ")
            .atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(deadLetteredKeys(DLQ_TOPIC)).contains(stored.id().toString()));
    }
}
