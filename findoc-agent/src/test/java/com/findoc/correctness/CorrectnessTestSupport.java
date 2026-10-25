package com.findoc.correctness;

import com.findoc.entity.Document;
import com.findoc.entity.DocumentSource;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.messaging.IngestionProducer;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentSourceRepository;
import com.findoc.repository.TenantRepository;
import com.findoc.repository.UserRepository;
import com.findoc.service.embedding.EmbeddingService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared Testcontainers setup (pgvector Postgres + Kafka) for the distributed-systems correctness tests.
 * Every test in this package asserts the DESIRED guarantee and is expected to FAIL against the current code;
 * see docs/audit/correctness-audit.md for the finding each one proves.
 */
@Tag("correctness")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.kafka.listener.auto-startup=true",
    "spring.kafka.listener.ack-mode=record",
    "spring.kafka.listener.missing-topics-fatal=false",
    "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
    "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
    "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
    "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.properties.spring.json.trusted.packages=com.findoc.messaging",
    "spring.kafka.consumer.properties.spring.json.value.default.type=com.findoc.messaging.IngestionJob",
    "findoc.ingestion.retry.initial-interval-ms=200",
    "findoc.ingestion.retry.multiplier=2.0",
    "findoc.ingestion.retry.max-interval-ms=500",
    "findoc.ingestion.retry.max-retries=2"
})
abstract class CorrectnessTestSupport {
    static final int EMBEDDING_DIMENSIONS = 768;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
        .withDatabaseName("findoc")
        .withUsername("findoc")
        .withPassword("findoc");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.change-log", () -> "classpath:/db/changelog/db.changelog-master.xml");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @MockBean
    EmbeddingService embeddingService;

    @SpyBean
    IngestionProducer ingestionProducer;

    @Autowired
    TenantRepository tenantRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    DocumentRepository documentRepository;
    @Autowired
    DocumentSourceRepository sourceRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    record Account(Tenant tenant, User user) {}

    record StoredDocument(Account account, Document document) {
        UUID id() {
            return document.getId();
        }
    }

    @BeforeEach
    void stubEmbeddingAndAwaitListener() {
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenReturn(vector());
        Awaitility.await().atMost(Duration.ofSeconds(60))
            .untilAsserted(() -> assertThat(listenerRegistry.getListenerContainers())
                .anySatisfy(container -> assertThat(container.getAssignedPartitions()).isNotEmpty()));
    }

    static float[] vector() {
        float[] embedding = new float[EMBEDDING_DIMENSIONS];
        embedding[0] = 1.0f;
        return embedding;
    }

    Account newAccount() {
        Tenant tenant = tenantRepository.saveAndFlush(new Tenant("correctness-" + UUID.randomUUID()));
        User user = userRepository.saveAndFlush(new User(tenant, "user-" + UUID.randomUUID(),
            "correctness@example.test", "test-password-hash"));
        return new Account(tenant, user);
    }

    /** Commits a PENDING document plus its BYTEA source, exactly as a successful upload transaction would. */
    StoredDocument newPendingDocument(String text) {
        Account account = newAccount();
        Document document = documentRepository.saveAndFlush(
            new Document(account.tenant(), account.user(), "correctness.txt", "text/plain"));
        sourceRepository.saveAndFlush(new DocumentSource(document, account.tenant(), text.getBytes()));
        return new StoredDocument(account, document);
    }

    /** Space separated unique tokens so every produced chunk has distinct content. */
    static String words(String prefix, int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) {
            text.append(prefix).append('_').append(index).append(' ');
        }
        return text.toString().trim();
    }

    String status(UUID documentId) {
        return jdbc.queryForObject("select status from documents where id = ?", String.class, documentId);
    }

    int liveChunks(UUID documentId) {
        return jdbc.queryForObject(
            "select count(*) from document_chunks where document_id = ? and deleted_at is null", Integer.class, documentId);
    }

    /** Keys (document ids) of every record currently on the dead-letter topic. */
    Set<String> deadLetteredKeys(String dlqTopic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        Set<String> keys = new HashSet<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(dlqTopic));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> keys.add(record.key()));
            }
        }
        return keys;
    }
}
