package com.findoc.correctness;

import com.findoc.messaging.IngestionJob;
import com.findoc.service.document.DocumentService;
import com.findoc.service.document.IngestionService;
import com.findoc.service.document.IngestionProgressService;
import com.findoc.messaging.StaleIngestionRecovery;
import com.findoc.service.embedding.GeminiEmbeddingService;
import com.findoc.util.TenantContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Audit 2: the ingestion consumer must be idempotent under redelivery, concurrency, failure and deletion. */
class IngestionIdempotencyIntegrationTest extends SharedPipelineTestSupport {
    private static final int WORDS_PER_CHUNK_STEP = 462;

    @Autowired
    IngestionService ingestionService;
    @Autowired
    StaleIngestionRecovery staleIngestionRecovery;
    @Autowired
    IngestionProgressService ingestionProgressService;
    @Autowired
    DocumentService documentService;
    private static String uniquePrefix(String label) {
        return label + UUID.randomUUID().toString().replace("-", "");
    }

    private static IngestionJob jobFor(StoredDocument stored) {
        return new IngestionJob(stored.id(), stored.account().tenant().getId(), stored.account().user().getId());
    }

    private void awaitStatus(UUID documentId, String expected, Duration timeout) {
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertThat(status(documentId)).isEqualTo(expected));
    }

    /** Audit 2 (c): only one non-deleted row may exist for a document/index pair. */
    @Test
    void chunkTableRejectsTwoLiveChunksWithTheSameDocumentAndIndex() {
        StoredDocument stored = newPendingDocument("unused");
        String insert = "insert into document_chunks (id, document_id, tenant_id, chunk_index, content) values (?, ?, ?, 0, 'c')";
        jdbc.update(insert, UUID.randomUUID(), stored.id(), stored.account().tenant().getId());

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), stored.id(), stored.account().tenant().getId()))
            .as("a second live chunk (document_id, chunk_index) must violate a unique index")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chunkTableAllowsSameIndexAfterPreviousChunkIsSoftDeleted() {
        StoredDocument stored = newPendingDocument("unused");
        String insert = "insert into document_chunks (id, document_id, tenant_id, chunk_index, content) values (?, ?, ?, 0, 'c')";
        jdbc.update(insert, UUID.randomUUID(), stored.id(), stored.account().tenant().getId());
        jdbc.update("update document_chunks set deleted_at = current_timestamp where document_id = ? and chunk_index = 0",
            stored.id());

        jdbc.update(insert, UUID.randomUUID(), stored.id(), stored.account().tenant().getId());

        assertThat(liveChunks(stored.id())).isEqualTo(1);
    }

    @Test
    void ingestionClaimDoesNotCrossTenantBoundary() {
        StoredDocument stored = newPendingDocument(words(uniquePrefix("tenant"), 50));
        IngestionJob wrongTenantJob = new IngestionJob(
            stored.id(), UUID.randomUUID(), stored.account().user().getId());

        assertThatThrownBy(() -> ingestionService.ingest(wrongTenantJob))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Document not found for tenant");

        assertThat(status(stored.id())).isEqualTo("PENDING");
        assertThat(liveChunks(stored.id())).isZero();
    }

    /** Audit 2 (b): embedding provider calls run outside database transactions. */
    @Test
    void embeddingProviderIsNotCalledInsideAnOpenDatabaseTransaction() throws Exception {
        StoredDocument stored = newPendingDocument(words(uniquePrefix("tx"), 50));
        AtomicBoolean transactionOpenDuringEmbed = new AtomicBoolean(false);
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenAnswer(invocation -> {
            transactionOpenDuringEmbed.set(TransactionSynchronizationManager.isActualTransactionActive());
            return vector();
        });

        ingestionService.ingest(jobFor(stored));

        assertThat(transactionOpenDuringEmbed)
            .as("external HTTP calls must not run while a DB transaction/connection is held")
            .isFalse();
    }

    /** Audit 2 (d)/(e), timeline 2: a duplicate delivery of an already-READY document is a no-op. */
    @Test
    void redeliveredMessageForReadyDocumentIsAcknowledgedAsNoOp() {
        StoredDocument first = newPendingDocument(words(uniquePrefix("dup"), 50));
        ingestionProducer.publish(jobFor(first));
        awaitStatus(first.id(), "READY", Duration.ofSeconds(30));
        int chunksAfterFirstDelivery = liveChunks(first.id());

        StoredDocument followUp = newPendingDocument(words(uniquePrefix("next"), 50));
        ingestionProducer.publish(jobFor(first)); // duplicate delivery of the same message
        ingestionProducer.publish(jobFor(followUp)); // same key/partition: only reachable once the duplicate is resolved

        Awaitility.await()
            .alias("a duplicate of a completed job must not wedge the partition behind it")
            .atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertThat(status(followUp.id())).isEqualTo("READY"));

        assertThat(status(first.id())).isEqualTo("READY");
        assertThat(liveChunks(first.id())).isEqualTo(chunksAfterFirstDelivery);
        assertThat(deadLetteredKeys(DLQ_TOPIC))
            .as("an idempotent no-op must not be dead-lettered")
            .doesNotContain(first.id().toString());
        assertThat(jdbc.queryForObject("select retry_count from documents where id = ?", Integer.class, first.id()))
            .as("a duplicate must not count as a failed attempt").isZero();
    }

    @Test
    void staleProcessingClaimIsRequeuedThroughTransactionalOutbox() {
        String content = words(uniquePrefix("stale"), 50);
        StoredDocument stored = newPendingDocument(content);
        jdbc.update("""
            update documents
            set status = 'PROCESSING'
            where id = ?
            """, stored.id());
        ingestionProgressService.save(stored.id(), stored.account().tenant().getId(), 0, content, 50,
            java.util.Arrays.toString(vector()));
        jdbc.update("update documents set updated_at = current_timestamp - interval '10 minutes' where id = ?",
            stored.id());

        staleIngestionRecovery.recoverStaleIngestions();

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
            .until(() -> List.of("READY", "FAILED").contains(status(stored.id())));
        assertThat(status(stored.id()))
            .as("ingestion recovery error_msg: %s",
                jdbc.queryForObject("select error_msg from documents where id = ?", String.class, stored.id()))
            .isEqualTo("READY");
        assertThat(jdbc.queryForObject("select retry_count from documents where id = ?", Integer.class, stored.id()))
            .as("recovery records the interrupted attempt").isEqualTo(1);
        Mockito.verify(embeddingService, Mockito.never()).embed(Mockito.argThat(text -> text.startsWith("stale")));
    }

    @Test
    void reingestionUpsertsExistingChunksAndSoftDeletesRemovedTail() throws Exception {
        String prefix = uniquePrefix("upsert");
        StoredDocument stored = newPendingDocument(words(prefix, 600));
        ingestionProducer.publish(jobFor(stored));
        awaitStatus(stored.id(), "READY", Duration.ofSeconds(30));

        UUID firstChunkId = jdbc.queryForObject(
            "select id from document_chunks where document_id = ? and chunk_index = 0 and deleted_at is null",
            UUID.class, stored.id());
        UUID secondChunkId = jdbc.queryForObject(
            "select id from document_chunks where document_id = ? and chunk_index = 1 and deleted_at is null",
            UUID.class, stored.id());
        jdbc.update("update documents set status = 'FAILED' where id = ?", stored.id());
        jdbc.update("update document_sources set content = ? where document_id = ? and tenant_id = ?",
            words(prefix + "_updated", 50).getBytes(), stored.id(), stored.account().tenant().getId());

        ingestionService.ingest(jobFor(stored));

        UUID reingestedFirstChunkId = jdbc.queryForObject(
            "select id from document_chunks where document_id = ? and chunk_index = 0 and deleted_at is null",
            UUID.class, stored.id());
        assertThat(reingestedFirstChunkId).isEqualTo(firstChunkId);
        assertThat(jdbc.queryForObject(
            "select deleted_at is not null from document_chunks where id = ?", Boolean.class, secondChunkId)).isTrue();
        assertThat(liveChunks(stored.id())).isEqualTo(1);
        assertThat(status(stored.id())).isEqualTo("READY");
    }

    /** Audit 2 (e)/(f), timeline 3: two consumers (e.g. after a rebalance) ingesting the same document at once. */
    @Test
    void concurrentDeliveriesOfSameMessageDoNotDuplicateLiveChunks() throws Exception {
        StoredDocument stored = newPendingDocument(words(uniquePrefix("race"), 50)); // exactly one chunk
        CountDownLatch firstEmbeddingStarted = new CountDownLatch(1);
        CountDownLatch allowFirstEmbeddingToFinish = new CountDownLatch(1);
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenAnswer(invocation -> {
            firstEmbeddingStarted.countDown();
            assertThat(allowFirstEmbeddingToFinish.await(10, TimeUnit.SECONDS)).isTrue();
            return vector();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstAttempt = pool.submit((Callable<Void>) () -> {
                ingestionService.ingest(jobFor(stored));
                return null;
            });
            assertThat(firstEmbeddingStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> secondAttempt = pool.submit((Callable<Void>) () -> {
                ingestionService.ingest(jobFor(stored));
                return null;
            });
            assertThatThrownBy(() -> secondAttempt.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
            allowFirstEmbeddingToFinish.countDown();
            firstAttempt.get(10, TimeUnit.SECONDS);
        } finally {
            allowFirstEmbeddingToFinish.countDown();
            pool.shutdownNow();
        }

        assertThat(liveChunks(stored.id()))
            .as("one source chunk must yield exactly one live chunk no matter how many consumers ran")
            .isEqualTo(1);
        Mockito.verify(embeddingService, Mockito.times(1)).embed(Mockito.anyString());
    }

    /** Audit 2 (g), timeline 4: successful chunk embeddings are retained across provider retries. */
    @Test
    void retryAfterMidwayEmbeddingFailureDoesNotReEmbedCompletedChunks() {
        String prefix = uniquePrefix("fail");
        int totalWords = 19 * WORDS_PER_CHUNK_STEP + 100; // 20 chunks
        StoredDocument stored = newPendingDocument(words(prefix, totalWords));
        String fifteenthChunkStart = prefix + "_" + (14 * WORDS_PER_CHUNK_STEP) + " ";
        Map<String, AtomicInteger> callsPerChunk = new ConcurrentHashMap<>();
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenAnswer(invocation -> {
            String content = invocation.getArgument(0);
            callsPerChunk.computeIfAbsent(content, key -> new AtomicInteger()).incrementAndGet();
            if (content.startsWith(fifteenthChunkStart)) {
                throw new GeminiEmbeddingService.EmbeddingException("provider failure on chunk 15");
            }
            return vector();
        });

        ingestionProducer.publish(jobFor(stored));
        awaitStatus(stored.id(), "FAILED", Duration.ofSeconds(60));

        assertThat(liveChunks(stored.id())).as("failed ingestion must leave no partial live chunks").isZero();
        assertThat(callsPerChunk.entrySet().stream()
            .filter(entry -> !entry.getKey().startsWith(fifteenthChunkStart))
            .mapToInt(entry -> entry.getValue().get()).max().orElse(0))
            .as("successful embedding calls must be reused across retries")
            .isEqualTo(1);
        assertThat(callsPerChunk.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(fifteenthChunkStart))
            .findFirst().orElseThrow().getValue().get())
            .as("the provider-failing chunk is retried, but completed chunks are not re-embedded")
            .isEqualTo(3);
        assertThat(jdbc.queryForObject("select retry_count from documents where id = ?", Integer.class, stored.id()))
            .as("retry_count must reflect all three delivery attempts").isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "select count(*) from ingestion_chunk_embeddings where document_id = ?", Integer.class, stored.id()))
            .as("successful embeddings remain staged for an explicit future replay").isEqualTo(14);
    }

    /** Audit 2 (i): ingestion started before a delete overwrites deleted_at and writes live chunks afterwards. */
    @Test
    void documentDeletedDuringIngestionStaysDeletedWithNoLiveChunks() throws Exception {
        StoredDocument stored = newPendingDocument(words(uniquePrefix("del"), 50));
        CountDownLatch inEmbedding = new CountDownLatch(1);
        CountDownLatch deleteCommitted = new CountDownLatch(1);
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenAnswer(invocation -> {
            inEmbedding.countDown();
            deleteCommitted.await(30, TimeUnit.SECONDS);
            return vector();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> ingestion = pool.submit(() -> {
                ingestionService.ingest(jobFor(stored));
                return null;
            });
            assertThat(inEmbedding.await(30, TimeUnit.SECONDS)).isTrue();

            TenantContext.set(stored.account().tenant().getId(), stored.account().user().getId());
            try {
                documentService.delete(stored.id()); // commits while ingest() holds its stale snapshot
            } finally {
                TenantContext.clear();
            }
            deleteCommitted.countDown();
            try {
                ingestion.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ignored) {
                // aborting the in-flight ingestion is an acceptable outcome
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select deleted_at from documents where id = ?", java.sql.Timestamp.class, stored.id()))
            .as("a deleted document must not be resurrected by an in-flight ingestion").isNotNull();
        assertThat(liveChunks(stored.id())).as("no live chunks may exist for a deleted document").isZero();
    }
}
