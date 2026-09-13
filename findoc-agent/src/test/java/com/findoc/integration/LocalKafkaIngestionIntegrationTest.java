package com.findoc.integration;

import com.findoc.entity.Document;
import com.findoc.entity.DocumentSource;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.messaging.IngestionJob;
import com.findoc.messaging.IngestionProducer;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentSourceRepository;
import com.findoc.repository.TenantRepository;
import com.findoc.repository.UserRepository;
import com.findoc.service.embedding.EmbeddingService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local-integration")
@ActiveProfiles("local-pg")
@TestPropertySource(properties = {
    "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
    "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
    "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
    "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.properties.spring.json.trusted.packages=com.findoc.messaging",
    "spring.kafka.consumer.properties.spring.json.value.default.type=com.findoc.messaging.IngestionJob",
    "findoc.ingestion.topic=local-findoc-ingestion-test",
    "findoc.ingestion.dlq=local-findoc-ingestion-test-dlq",
    "findoc.ingestion.group=local-kafka-ingestion-test"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalKafkaIngestionIntegrationTest {
    private static final String TEST_TOPIC = "local-findoc-ingestion-" + UUID.randomUUID();
    private static final String TEST_DLQ = TEST_TOPIC + "-dlq";
    private static final String TEST_GROUP = TEST_TOPIC + "-group";

    @DynamicPropertySource
    static void registerKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("findoc.ingestion.topic", () -> TEST_TOPIC);
        registry.add("findoc.ingestion.dlq", () -> TEST_DLQ);
        registry.add("findoc.ingestion.group", () -> TEST_GROUP);
    }

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentSourceRepository sourceRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private IngestionProducer ingestionProducer;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @MockBean
    private EmbeddingService embeddingService;

    @Test
    void publishesKafkaJobAndProcessesDocumentFromPostgresSource() {
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenReturn(embeddingWithValue(1.0f));

        Tenant tenant = tenantRepository.saveAndFlush(new Tenant("kafka-integration-" + UUID.randomUUID()));
        User user = userRepository.saveAndFlush(new User(tenant, "user-" + UUID.randomUUID(),
            "kafka@example.test", "test-password-hash"));
        Document document = documentRepository.saveAndFlush(
            new Document(tenant, user, "ingestion.txt", "text/plain"));
        sourceRepository.saveAndFlush(new DocumentSource(document, tenant,
            "Kafka delivered this text from PostgreSQL BYTEA storage.".getBytes()));

        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(listenerRegistry.getListenerContainers())
                .anySatisfy(container -> assertThat(container.getAssignedPartitions()).isNotEmpty()));

        ingestionProducer.publish(new IngestionJob(document.getId(), tenant.getId(), user.getId()));

        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                Document processed = documentRepository.findById(document.getId()).orElseThrow();
                assertThat(processed.getStatus()).isEqualTo(Document.Status.READY);
                assertThat(chunkRepository.countByDocumentIdAndTenantId(document.getId(), tenant.getId()))
                    .isPositive();
            });

        Mockito.verify(embeddingService, Mockito.atLeastOnce()).embed(Mockito.anyString());
    }

    private static float[] embeddingWithValue(float value) {
        float[] embedding = new float[768];
        embedding[0] = value;
        return embedding;
    }
}