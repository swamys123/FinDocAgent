package com.findoc.correctness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.findoc.controller.DocumentController;
import com.findoc.entity.DocumentChunk;
import com.findoc.dto.response.AgentResponse;
import com.findoc.exception.GlobalExceptionHandler;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.service.document.DocumentService;
import com.findoc.service.idempotency.IdempotencyExecutor;
import com.findoc.util.TenantContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Audit 4: submitting the same upload twice must not create two documents, two ingestions or two sets of citations. */
class UploadIdempotencyIntegrationTest extends SharedPipelineTestSupport {
    @Autowired
    DocumentService documentService;
    @Autowired
    DocumentController documentController;
    @Autowired
    DocumentChunkRepository chunkRepository;
    @Autowired
    IdempotencyExecutor idempotencyExecutor;

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Audit 4 a / timeline 1: client times out, retries with the same Idempotency-Key; the first call had succeeded. */
    @Test
    void retryWithSameIdempotencyKeyReturnsOriginalDocument() throws Exception {
        Account account = newAccount();
        String key = UUID.randomUUID().toString();
        byte[] content = words(unique("retry"), 40).getBytes();

        UploadResult first = postUpload(account, key, "report.txt", content);
        UploadResult retry = postUpload(account, key, "report.txt", content);

        assertThat(retry.documentId).as("the replayed request must return the document created by the first request")
            .isEqualTo(first.documentId);
        assertThat(first.status).isEqualTo(202);
        assertThat(first.replayed).isFalse();
        assertThat(retry.status).isEqualTo(first.status);
        assertThat(retry.replayed).isTrue();
        assertThat(documentCount(account)).as("documents created for one logical upload").isEqualTo(1);
    }

    /** Audit 4 a: reusing a key with a different payload must be rejected. */
    @Test
    void idempotencyKeyReusedWithDifferentContentIsRejected() throws Exception {
        Account account = newAccount();
        String key = UUID.randomUUID().toString();

        postUpload(account, key, "report.txt", words(unique("first"), 40).getBytes());
        UploadResult second = postUpload(account, key, "report.txt", words(unique("second"), 40).getBytes());

        assertThat(second.status).as("key reuse with a different payload").isEqualTo(422);
        assertThat(documentCount(account)).isEqualTo(1);
    }

    @Test
    void sameKeyIsIsolatedBetweenTenants() throws Exception {
        Account firstAccount = newAccount();
        Account secondAccount = newAccount();
        String key = UUID.randomUUID().toString();
        byte[] content = words(unique("tenant-key"), 40).getBytes();

        UploadResult first = postUpload(firstAccount, key, "report.txt", content);
        UploadResult second = postUpload(secondAccount, key, "report.txt", content);

        assertThat(second.replayed).isFalse();
        assertThat(second.documentId).isNotEqualTo(first.documentId);
        assertThat(documentCount(firstAccount)).isEqualTo(1);
        assertThat(documentCount(secondAccount)).isEqualTo(1);
    }

    @Test
    void concurrentUploadsWithSameKeyCreateOneDocument() throws Exception {
        Account account = newAccount();
        String key = UUID.randomUUID().toString();
        byte[] content = words(unique("concurrent-key"), 40).getBytes();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> upload = () -> {
                TenantContext.set(account.tenant().getId(), account.user().getId());
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    var response = documentController.upload(
                        new MockMultipartFile("file", "report.txt", "text/plain", content), key);
                    return Boolean.parseBoolean(response.getHeaders().getFirst("Idempotent-Replayed"));
                } finally {
                    TenantContext.clear();
                }
            };
            List<Future<Boolean>> results = List.of(pool.submit(upload), pool.submit(upload));
            List<Boolean> replayed = results.stream().map(result -> {
                try {
                    return result.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("Concurrent idempotent upload failed", exception);
                }
            }).toList();

            assertThat(replayed).containsExactlyInAnyOrder(false, true);
        } finally {
            pool.shutdownNow();
        }
        assertThat(documentCount(account)).as("documents created by concurrent requests with one key").isEqualTo(1);
    }

    @Test
    void expiredKeyCanBeUsedForANewRequest() throws Exception {
        Account account = newAccount();
        String key = UUID.randomUUID().toString();
        UploadResult first = postUpload(account, key, "report.txt", words(unique("old"), 40).getBytes());
        jdbc.update("update idempotency_keys set expires_at = now() - interval '1 second' "
            + "where tenant_id = ? and idempotency_key = ?", account.tenant().getId(), key);

        UploadResult afterExpiry = postUpload(account, key, "report.txt", words(unique("new"), 40).getBytes());

        assertThat(afterExpiry.status).isEqualTo(202);
        assertThat(afterExpiry.replayed).isFalse();
        assertThat(afterExpiry.documentId).isNotEqualTo(first.documentId);
        assertThat(documentCount(account)).isEqualTo(2);
    }

    @Test
    void failedOperationRollsBackItsKeyReservation() {
        Account account = newAccount();
        String key = UUID.randomUUID().toString();
        String fingerprint = "a".repeat(64);

        assertThatThrownBy(() -> idempotencyExecutor.execute(
            account.tenant().getId(), key, fingerprint, HttpStatus.OK, String.class,
            () -> { throw new IllegalStateException("operation failed"); }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("operation failed");

        assertThat(jdbc.queryForObject(
            "select count(*) from idempotency_keys where tenant_id = ? and idempotency_key = ?",
            Integer.class, account.tenant().getId(), key)).isZero();

        var retry = idempotencyExecutor.execute(
            account.tenant().getId(), key, fingerprint, HttpStatus.OK, String.class, () -> "stored");
        assertThat(retry.body()).isEqualTo("stored");
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    void storedAgentResponseIsDeserializedForReplay() {
        Account account = newAccount();
        String key = UUID.randomUUID().toString();
        AgentResponse expected = new AgentResponse(
            UUID.randomUUID(), UUID.randomUUID(), "answer", "FACTUAL", List.of(), List.of("classify_intent"), 0.9);

        idempotencyExecutor.execute(
            account.tenant().getId(), key, "b".repeat(64), HttpStatus.OK, AgentResponse.class, () -> expected);
        var replay = idempotencyExecutor.execute(
            account.tenant().getId(), key, "b".repeat(64), HttpStatus.OK, AgentResponse.class,
            () -> { throw new AssertionError("Replay must not rerun the operation"); });

        assertThat(replay.body()).isEqualTo(expected);
        assertThat(replay.replayed()).isTrue();
    }

    /** Audit 4 c, timeline 2: double click sends two concurrent identical requests. */
    @Test
    void concurrentIdenticalUploadsCreateASingleDocument() throws Exception {
        Account account = newAccount();
        byte[] content = words(unique("double"), 40).getBytes();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> upload = () -> {
                TenantContext.set(account.tenant().getId(), account.user().getId());
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    documentService.upload(new MockMultipartFile("file", "report.txt", "text/plain", content));
                } finally {
                    TenantContext.clear();
                }
                return null;
            };
            List<Future<Void>> results = List.of(pool.submit(upload), pool.submit(upload));
            for (Future<Void> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // a rejected duplicate is acceptable; only the stored row count is asserted
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(documentCount(account)).as("live documents after two concurrent identical uploads").isEqualTo(1);
    }

    @Test
    void identicalContentIsDeduplicatedWithinTenantOnly() throws Exception {
        Account firstAccount = newAccount();
        Account secondAccount = newAccount();
        byte[] content = words(unique("tenant-content"), 40).getBytes();

        UUID first = postUpload(firstAccount, null, "report.txt", content).documentId;
        UUID second = postUpload(secondAccount, null, "report.txt", content).documentId;

        assertThat(second).isNotEqualTo(first);
        assertThat(documentCount(firstAccount)).isEqualTo(1);
        assertThat(documentCount(secondAccount)).isEqualTo(1);
    }

    @Test
    void softDeletedContentCanBeUploadedAgain() throws Exception {
        Account account = newAccount();
        byte[] content = words(unique("deleted-content"), 40).getBytes();

        UUID first = postUpload(account, null, "report.txt", content).documentId;
        jdbc.update("update documents set deleted_at = current_timestamp where id = ? and tenant_id = ?",
            first, account.tenant().getId());

        UploadResult replacement = postUpload(account, null, "report.txt", content);

        assertThat(replacement.documentId).isNotEqualTo(first);
        assertThat(replacement.duplicate).isFalse();
        assertThat(documentCount(account)).isEqualTo(1);
    }

    @Test
    void duplicateUploadRequeuesFailedDocument() throws Exception {
        Account account = newAccount();
        byte[] content = words(unique("failed-content"), 40).getBytes();
        UUID first = postUpload(account, null, "report.txt", content).documentId;
        jdbc.update("update documents set status = 'FAILED', error_msg = 'test failure' where id = ? and tenant_id = ?",
            first, account.tenant().getId());

        UploadResult duplicate = postUpload(account, null, "report.txt", content);

        assertThat(duplicate.documentId).isEqualTo(first);
        assertThat(duplicate.duplicate).isTrue();
        assertThat(duplicate.status).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(jdbc.queryForObject("select status from documents where id = ? and tenant_id = ?",
            String.class, first, account.tenant().getId())).isIn("PENDING", "PROCESSING", "READY");
        assertThat(jdbc.queryForObject("select error_msg from documents where id = ? and tenant_id = ?",
            String.class, first, account.tenant().getId())).isNull();
    }

    /** Audit 4 c, timeline 3: the same file uploaded again a day later must resolve to the existing document. */
    @Test
    void sameContentReuploadedLaterReturnsExistingDocumentWithoutNewIngestion() throws Exception {
        Account account = newAccount();
        byte[] content = words(unique("later"), 40).getBytes();

        UUID first = postUpload(account, null, "report.txt", content).documentId;
        jdbc.update("update documents set created_at = now() - interval '1 day' where id = ?", first);
        Mockito.clearInvocations(ingestionProducer);
        UploadResult duplicate = postUpload(account, null, "report.txt", content);

        assertThat(duplicate.documentId).as("same tenant and same content hash").isEqualTo(first);
        assertThat(duplicate.duplicate).isTrue();
        assertThat(documentCount(account)).isEqualTo(1);
        Mockito.verify(ingestionProducer, Mockito.never()).publish(Mockito.any());
    }

    /** Audit 4 b: uploads persist a SHA-256 content hash on the document. */
    @Test
    void contentHashIsStoredPerDocument() throws Exception {
        Account account = newAccount();
        byte[] content = words(unique("hash"), 40).getBytes();
        UUID documentId = postUpload(account, null, "hash.txt", content).documentId;
        String storedHash = jdbc.queryForObject(
            "select content_hash from documents where id = ? and tenant_id = ?",
            String.class, documentId, account.tenant().getId());
        String expectedHash = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(content));

        assertThat(storedHash).isEqualTo(expectedHash);
    }

    /** Audit 4 b: no (tenant_id, content_hash) uniqueness exists to arbitrate races between concurrent uploads. */
    @Test
    void databaseEnforcesOneLiveDocumentPerTenantAndContentHash() {
        List<String> indexes = jdbc.queryForList(
            "select indexdef from pg_indexes where tablename = 'documents' "
                + "and indexdef ilike 'create unique index%' and indexdef like '%tenant_id%' "
                + "and (indexdef like '%hash%' or indexdef like '%checksum%')",
            String.class);

        assertThat(indexes).as("unique index over (tenant_id, content hash) on documents").isNotEmpty();
    }

    /** Audit 4 e: a duplicate upload doubles the paid embedding calls. */
    @Test
    void duplicateUploadDoesNotEmbedTheSameContentTwice() throws Exception {
        Account account = newAccount();
        String marker = unique("embed");
        byte[] content = words(marker, 40).getBytes();

        postUpload(account, null, "report.txt", content);
        postUpload(account, null, "report.txt", content);
        awaitAllTerminal(account);

        Mockito.verify(embeddingService, Mockito.times(1)).embed(ArgumentMatchers.contains(marker));
    }

    /** Audit 4 e: duplicate documents fill the top-k with identical chunks, which the agent then cites twice. */
    @Test
    void duplicateUploadDoesNotProduceDuplicateRetrievalResults() throws Exception {
        Account account = newAccount();
        byte[] content = words(unique("cite"), 40).getBytes();

        postUpload(account, null, "report.txt", content);
        postUpload(account, null, "report.txt", content);
        awaitAllTerminal(account);

        List<DocumentChunk> hits = chunkRepository.searchSimilar(vector(), account.tenant().getId(), 10);
        long distinctContents = hits.stream().map(DocumentChunk::getContent).distinct().count();
        assertThat(documentCount(account)).isEqualTo(1);
        assertThat(hits).as("retrieval hits for one logical document").isNotEmpty().hasSize((int) distinctContents);
    }

    private record UploadResult(int status, UUID documentId, boolean replayed, boolean duplicate) {}

    private UploadResult postUpload(Account account, String idempotencyKey, String filename, byte[] content) throws Exception {
        TenantContext.set(account.tenant().getId(), account.user().getId());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(documentController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        var request = multipart("/api/v1/documents/upload")
            .file(new MockMultipartFile("file", filename, "text/plain", content));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        UUID id = null;
        if (!body.isBlank()) {
            JsonNode node = json.readTree(body);
            if (node.hasNonNull("documentId")) {
                id = UUID.fromString(node.get("documentId").asText());
            }
        }
        return new UploadResult(
            result.getResponse().getStatus(),
            id,
            Boolean.parseBoolean(result.getResponse().getHeader("Idempotent-Replayed")),
            !body.isBlank() && json.readTree(body).path("duplicate").asBoolean());
    }

    private int documentCount(Account account) {
        return jdbc.queryForObject(
            "select count(*) from documents where tenant_id = ? and deleted_at is null", Integer.class, account.tenant().getId());
    }

    private void awaitAllTerminal(Account account) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
            assertThat(jdbc.queryForObject(
                "select count(*) from documents where tenant_id = ? and deleted_at is null and status <> 'READY'",
                Integer.class, account.tenant().getId()))
                .as("all documents in a successful upload scenario must reach READY")
                .isZero());
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
