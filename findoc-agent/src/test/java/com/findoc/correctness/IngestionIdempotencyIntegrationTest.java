package com.findoc.correctness;

import com.findoc.messaging.IngestionJob;
import com.findoc.service.document.DocumentService;
import com.findoc.service.document.IngestionService;
import com.findoc.service.embedding.GeminiEmbeddingService;
import com.findoc.util.TenantContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
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

    /** Audit 2 (c): document_chunks has no UNIQUE (tenant/document, chunk_index) constraint at all. */
    @Test
    void chunkTableRejectsTwoLiveChunksWithTheSameDocumentAndIndex() {
        StoredDocument stored = newPendingDocument("unused");
        String insert = "insert into document_chunks (id, document_id, tenant_id, chunk_index, content) values (?, ?, ?, 0, 'c')";
        jdbc.update(insert, UUID.randomUUID(), stored.id(), stored.account().tenant().getId());

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), stored.id(), stored.account().tenant().getId()))
            .as("a second live chunk (document_id, chunk_index) must violate a unique index")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Audit 2 (b): the Gemini HTTP call is made inside the open @Transactional ingest(). */
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

    /** Audit 2 (d)/(e), timeline 2: a duplicate delivery of an already-READY document is treated as a failure. */
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

    /** Audit 2 (e)/(f), timeline 3: two consumers (e.g. after a rebalance) ingesting the same document at once. */
    @Test
    void concurrentDeliveriesOfSameMessageDoNotDuplicateLiveChunks() throws Exception {
        StoredDocument stored = newPendingDocument(words(uniquePrefix("race"), 50)); // exactly one chunk
        CountDownLatch bothEmbedding = new CountDownLatch(2);
        Mockito.when(embeddingService.embed(Mockito.anyString())).thenAnswer(invocation -> {
            bothEmbedding.countDown();
            bothEmbedding.await(5, TimeUnit.SECONDS); // line the two attempts up inside the pipeline
            return vector();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> attempts = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                Callable<Object> ingest = () -> {
                    ingestionService.ingest(jobFor(stored));
                    return null;
                };
                attempts.add(pool.submit(ingest));
            }
            for (Future<Object> attempt : attempts) {
                try {
                    attempt.get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // rejecting the second attempt is an acceptable way to be idempotent
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(liveChunks(stored.id()))
            .as("one source chunk must yield exactly one live chunk no matter how many consumers ran")
            .isEqualTo(1);
    }

    /** Audit 2 (g), timeline 4: provider fails on chunk 15 of 20; every retry re-pays for chunks 1-14. */
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
        assertThat(callsPerChunk.values().stream().mapToInt(AtomicInteger::get).max().orElse(0))
            .as("each chunk's paid embedding call must happen at most once across all retries; calls per chunk=%s",
                callsPerChunk.values())
            .isLessThanOrEqualTo(1);
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
