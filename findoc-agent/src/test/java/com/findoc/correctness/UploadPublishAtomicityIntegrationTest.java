package com.findoc.correctness;

import com.findoc.service.document.DocumentService;
import com.findoc.dto.response.DocumentResponse;
import com.findoc.messaging.IngestionJob;
import com.findoc.repository.OutboxRepository;
import com.findoc.util.TenantContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit 1: "persist the document" and "publish the ingestion message" must not diverge permanently. */
class UploadPublishAtomicityIntegrationTest extends SharedPipelineTestSupport {
    @Autowired
    DocumentService documentService;
    @Autowired
    OutboxRepository outboxRepository;

    /** Audit 1, timeline 2: a committed outbox event is eventually relayed after a process interruption. */
    @Test
    void committedPendingDocumentWithoutMessageIsEventuallyIngested() {
        StoredDocument orphan = newPendingDocument(words("orphan" + UUID.randomUUID().toString().replace("-", ""), 50));
        outboxRepository.enqueue(orphan.account().tenant().getId(),
            new IngestionJob(orphan.id(), orphan.account().tenant().getId(), orphan.account().user().getId()));
        // The document and unsent outbox event are committed, but the relay has not yet published the event.
        jdbc.update("update documents set created_at = now() - interval '1 day', updated_at = now() - interval '1 day' where id = ?",
            orphan.id());

        Awaitility.await()
            .alias("a committed unsent outbox event must be relayed and ingested")
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted(() -> {
                assertThat(status(orphan.id())).isEqualTo("READY");
                assertThat(jdbc.queryForObject(
                    "select count(*) from outbox where tenant_id = ? and payload ->> 'documentId' = ? and sent_at is not null",
                    Integer.class, orphan.account().tenant().getId(), orphan.id().toString()))
                    .as("the relay marks the published outbox event as sent")
                    .isEqualTo(1);
            });
    }

    /** Audit 1, timeline 3: Kafka down after commit surfaces as a 500 even though the upload is durably stored. */
    @Test
    void uploadSucceedsWhenBrokerIsUnavailableAfterCommit() throws Exception {
        Account account = newAccount();
        Mockito.doThrow(new IllegalStateException("broker unavailable")).when(ingestionProducer).publish(Mockito.any());

        TenantContext.set(account.tenant().getId(), account.user().getId());
        try {
            DocumentResponse response = documentService.upload(textFile());
            assertThat(jdbc.queryForObject(
                "select count(*) from outbox where tenant_id = ? and payload ->> 'documentId' = ? and sent_at is null",
                Integer.class, account.tenant().getId(), response.documentId().toString()))
                .as("broker failure leaves the committed ingestion event eligible for retry")
                .isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    /** Audit 1, timeline 3 / finding (d): send().get() has no timeout, so the request thread waits on the broker. */
    @Test
    void uploadDoesNotBlockRequestThreadOnUnresponsiveBroker() {
        Account account = newAccount();
        Mockito.doAnswer(invocation -> {
            TimeUnit.SECONDS.sleep(5);
            return null;
        }).when(ingestionProducer).publish(Mockito.any());

        TenantContext.set(account.tenant().getId(), account.user().getId());
        long startedNanos = System.nanoTime();
        try {
            documentService.upload(textFile());
        } catch (Exception ignored) {
            // outcome of the call is irrelevant here; only its latency is asserted
        } finally {
            TenantContext.clear();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertThat(elapsed)
            .as("upload must hand off publication instead of blocking the HTTP thread on the broker")
            .isLessThan(Duration.ofSeconds(2));
    }

    private static MockMultipartFile textFile() {
        return new MockMultipartFile("file", "upload.txt", "text/plain",
            words("upload" + UUID.randomUUID().toString().replace("-", ""), 40).getBytes());
    }
}
