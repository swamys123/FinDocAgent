package com.findoc.correctness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.findoc.controller.DocumentController;
import com.findoc.messaging.IngestionJob;
import com.findoc.service.document.DocumentService;
import com.findoc.util.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 3 (a2, b1, e3, f1, f2): what a dead-lettered job looks like, and what operators and users can see of it.
 * Own topics/context so the exhausted jobs cannot interfere with other Kafka tests.
 */
class DeadLetterContractIntegrationTest extends CorrectnessTestSupport {
    static final String TOPIC = "correctness-dlq-contract-" + UUID.randomUUID();
    static final String DLQ_TOPIC = TOPIC + ".dlq";
    // 1 initial delivery + findoc.ingestion.retry.max-retries=2
    static final int EXPECTED_ATTEMPTS = 3;

    @DynamicPropertySource
    static void registerTopics(DynamicPropertyRegistry registry) {
        registry.add("findoc.ingestion.topic", () -> TOPIC);
        registry.add("findoc.ingestion.dlq", () -> DLQ_TOPIC);
        registry.add("findoc.ingestion.group", () -> TOPIC + "-group");
    }

    @Autowired
    DocumentService documentService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    MeterRegistry meterRegistry;

    private StoredDocument exhaustRetries() {
        Mockito.when(embeddingService.embed(Mockito.anyString()))
            .thenThrow(new IllegalStateException("embedding-provider-down"));
        StoredDocument stored = newPendingDocument(words("dlq" + UUID.randomUUID().toString().replace("-", ""), 50));
        ingestionProducer.publish(new IngestionJob(stored.id(), stored.account().tenant().getId(), stored.account().user().getId()));
        Awaitility.await().alias("job must reach the DLQ after retries are exhausted")
            .atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> assertThat(deadLetteredKeys(DLQ_TOPIC)).contains(stored.id().toString()));
        return stored;
    }

    /** Audit 3 (a2): the retry count is never put on the DLQ record, so a replayer cannot cap replays. */
    @Test
    void deadLetteredMessageCarriesRetryCountHeader() {
        StoredDocument stored = exhaustRetries();

        ConsumerRecord<String, String> record = DeadLetterTopicReader.read(KAFKA.getBootstrapServers(), DLQ_TOPIC).stream()
            .filter(candidate -> stored.id().toString().equals(candidate.key()))
            .findFirst().orElseThrow();
        List<String> headerNames = StreamSupport.stream(record.headers().spliterator(), false)
            .map(header -> header.key().toLowerCase(Locale.ROOT)).toList();

        assertThat(headerNames).as("DLQ headers: %s", headerNames).anyMatch(name -> name.contains("retry"));
    }

    /** Audit 3 (b1): retry_count records each delivery attempt, not only final recovery. */
    @Test
    void failedDocumentRecordsTheRealNumberOfDeliveryAttempts() {
        StoredDocument stored = exhaustRetries();

        Integer retryCount = jdbc.queryForObject("select retry_count from documents where id = ?", Integer.class, stored.id());

        assertThat(retryCount).isEqualTo(EXPECTED_ATTEMPTS);
    }

    /** Audit 3 (e3): actuator exposes only health,info and nothing counts or logs dead-lettered jobs. */
    @Test
    void deadLetteringIsObservableThroughAMetric() {
        exhaustRetries();

        assertThat(meterRegistry.getMeters())
            .as("a counter/gauge for dead-lettered jobs or DLQ depth")
            .anyMatch(meter -> {
                String name = meter.getId().getName().toLowerCase(Locale.ROOT);
                return name.contains("dlq") || name.contains("dead");
            });
    }

    /** Audit 3 (f1): error_msg is stored but DocumentResponse has no field for it. */
    @Test
    void ownerCanSeeWhyTheirDocumentFailed() throws Exception {
        StoredDocument stored = exhaustRetries();

        JsonNode json;
        TenantContext.set(stored.account().tenant().getId(), stored.account().user().getId());
        try {
            json = objectMapper.valueToTree(documentService.status(stored.id()));
        } finally {
            TenantContext.clear();
        }

        assertThat(json.get("status").asText()).isEqualTo("FAILED");
        boolean hasReason = StreamSupport.stream(((Iterable<String>) json::fieldNames).spliterator(), false)
            .filter(name -> name.toLowerCase(Locale.ROOT).matches(".*(error|failure|reason).*"))
            .anyMatch(name -> !json.get(name).asText("").isBlank());
        assertThat(hasReason).as("status response has a failure reason field: %s", json).isTrue();
    }

    /** Audit 3 (c, f2): resetForRetry() exists on the entity but nothing exposes a retry/replay operation. */
    @Test
    void ownerCanRetriggerIngestionOfTheirFailedDocument() {
        boolean hasRetryEndpoint = Arrays.stream(DocumentController.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(PostMapping.class))
            .filter(java.util.Objects::nonNull)
            .flatMap(mapping -> Arrays.stream(mapping.value()))
            .anyMatch(path -> path.toLowerCase(Locale.ROOT).contains("retry"));

        assertThat(hasRetryEndpoint).as("POST /api/v1/documents/{id}/retry (or equivalent) exists").isTrue();
    }
}
