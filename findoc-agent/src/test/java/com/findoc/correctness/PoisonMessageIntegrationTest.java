package com.findoc.correctness;

import com.findoc.messaging.IngestionJob;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2 (h). Isolated on its own topic and Spring context: today a poison record wedges its consumer forever,
 * which would otherwise corrupt every other Kafka-based test sharing the topic.
 */
class PoisonMessageIntegrationTest extends CorrectnessTestSupport {
    static final String POISON_TOPIC = "correctness-poison-" + UUID.randomUUID();

    @DynamicPropertySource
    static void registerPoisonTopics(DynamicPropertyRegistry registry) {
        registry.add("findoc.ingestion.topic", () -> POISON_TOPIC);
        registry.add("findoc.ingestion.dlq", () -> POISON_TOPIC + ".dlq");
        registry.add("findoc.ingestion.group", () -> POISON_TOPIC + "-group");
    }

    /** No ErrorHandlingDeserializer is configured: an undeserialisable record must not block valid work behind it. */
    @Test
    void undeserialisableMessageDoesNotBlockSubsequentIngestion() throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (KafkaProducer<String, byte[]> raw = new KafkaProducer<>(props)) {
            raw.send(new ProducerRecord<>(POISON_TOPIC, "poison", "{ this is not an IngestionJob".getBytes()))
                .get(30, TimeUnit.SECONDS);
        }

        StoredDocument stored = newPendingDocument(words("poison" + UUID.randomUUID().toString().replace("-", ""), 50));
        ingestionProducer.publish(new IngestionJob(stored.id(), stored.account().tenant().getId(), stored.account().user().getId()));

        Awaitility.await()
            .alias("a poison record must be skipped/dead-lettered, not retried forever in front of valid work")
            .atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(status(stored.id())).isEqualTo("READY"));
    }
}
