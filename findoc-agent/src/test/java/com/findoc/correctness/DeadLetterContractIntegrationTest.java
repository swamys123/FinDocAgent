package com.findoc.correctness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.findoc.controller.AdminDlqReplayController;
import com.findoc.messaging.IngestionJob;
import com.findoc.service.document.DocumentService;
import com.findoc.service.document.DlqReplayService;
import com.findoc.util.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
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
    DlqReplayService dlqReplayService;
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

    /** Audit 3 (a2): delivery attempts are carried on the DLQ record for bounded replay. */
    @Test
    void deadLetteredMessageCarriesRetryCountHeader() {
        StoredDocument stored = exhaustRetries();

        ConsumerRecord<String, String> record = DeadLetterTopicReader.read(KAFKA.getBootstrapServers(), DLQ_TOPIC).stream()
            .filter(candidate -> stored.id().toString().equals(candidate.key()))
            .findFirst().orElseThrow();
        Header retryCountHeader = record.headers().lastHeader("findoc-retry-count");
        assertThat(retryCountHeader).as("DLQ retry-count header").isNotNull();
        assertThat(new String(retryCountHeader.value(), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo(Integer.toString(EXPECTED_ATTEMPTS));
    }

    /** Audit 3 (b1): retry_count records each delivery attempt, not only final recovery. */
    @Test
    void failedDocumentRecordsTheRealNumberOfDeliveryAttempts() {
        StoredDocument stored = exhaustRetries();

        Integer retryCount = jdbc.queryForObject("select retry_count from documents where id = ?", Integer.class, stored.id());

        assertThat(retryCount).isEqualTo(EXPECTED_ATTEMPTS);
    }

    /** Audit 3 (e3): a published dead-letter event increments the DLQ counter. */
    @Test
    void deadLetteringIsObservableThroughAMetric() {
        exhaustRetries();

        assertThat(meterRegistry.get("findoc.ingestion.dlq.published").counter().count()).isGreaterThanOrEqualTo(1);
    }

    /** Audit 3 (f1): a tenant-scoped status response exposes the failure reason. */
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
        assertThat(json.get("errorMessage").asText()).isEqualTo("embedding-provider-down");
    }

    /** Audit 3 (c, f2): replay the failed job through the transactional outbox without changing its tenant or owner. */
    @Test
    void adminReplayPreservesTenantAndOwnerAndReingestsDocument() throws Exception {
        StoredDocument stored = exhaustRetries();
        Mockito.doReturn(vector()).when(embeddingService).embed(Mockito.anyString());

        dlqReplayService.replay(stored.id(), stored.account().tenant().getId());

        Awaitility.await().alias("replayed job must complete ingestion")
            .atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(status(stored.id())).isEqualTo("READY"));

        String payload = jdbc.queryForObject(
            "select payload::text from outbox where tenant_id = ? and payload ->> 'documentId' = ?",
            String.class, stored.account().tenant().getId(), stored.id().toString());
        JsonNode replayJob = objectMapper.readTree(payload);
        assertThat(replayJob.get("documentId").asText()).isEqualTo(stored.id().toString());
        assertThat(replayJob.get("tenantId").asText()).isEqualTo(stored.account().tenant().getId().toString());
        assertThat(replayJob.get("userId").asText()).isEqualTo(stored.account().user().getId().toString());
        assertThat(jdbc.queryForObject(
            "select count(*) from outbox where tenant_id = ? and payload ->> 'documentId' = ?",
            Integer.class, stored.account().tenant().getId(), stored.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select retry_count from documents where id = ?", Integer.class, stored.id()))
            .isEqualTo(EXPECTED_ATTEMPTS);

        boolean hasReplayEndpoint = Arrays.stream(AdminDlqReplayController.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(PostMapping.class))
            .filter(java.util.Objects::nonNull)
            .flatMap(mapping -> Arrays.stream(mapping.value()))
            .anyMatch(path -> path.toLowerCase(Locale.ROOT).contains("replay"));

        assertThat(hasReplayEndpoint).as("POST /api/v1/admin/dlq/{documentId}/replay exists").isTrue();
    }
}
