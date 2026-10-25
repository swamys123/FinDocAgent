package com.findoc.correctness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.findoc.controller.DocumentController;
import com.findoc.entity.DocumentChunk;
import com.findoc.exception.GlobalExceptionHandler;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.service.document.DocumentService;
import com.findoc.util.TenantContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Audit 4: submitting the same upload twice must not create two documents, two ingestions or two sets of citations. */
class UploadIdempotencyIntegrationTest extends SharedPipelineTestSupport {
    @Autowired
    DocumentService documentService;
    @Autowired
    DocumentController documentController;
    @Autowired
    DocumentChunkRepository chunkRepository;

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

        UUID first = postUpload(account, key, "report.txt", content).documentId;
        UUID retry = postUpload(account, key, "report.txt", content).documentId;

        assertThat(retry).as("the replayed request must return the document created by the first request").isEqualTo(first);
        assertThat(documentCount(account)).as("documents created for one logical upload").isEqualTo(1);
    }

    /** Audit 4 a: the key is ignored today, so a reused key with a different payload is silently accepted. */
    @Test
    void idempotencyKeyReusedWithDifferentContentIsRejected() throws Exception {
        Account account = newAccount();
        String key = UUID.randomUUID().toString();

        postUpload(account, key, "report.txt", words(unique("first"), 40).getBytes());
        UploadResult second = postUpload(account, key, "report.txt", words(unique("second"), 40).getBytes());

        assertThat(second.status).as("key reuse with a different payload").isEqualTo(422);
        assertThat(documentCount(account)).isEqualTo(1);
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

    /** Audit 4 c, timeline 3: the same file uploaded again a day later must resolve to the existing document. */
    @Test
    void sameContentReuploadedLaterReturnsExistingDocumentWithoutNewIngestion() throws Exception {
        Account account = newAccount();
        byte[] content = words(unique("later"), 40).getBytes();

        UUID first = postUpload(account, null, "report.txt", content).documentId;
        jdbc.update("update documents set created_at = now() - interval '1 day' where id = ?", first);
        Mockito.clearInvocations(ingestionProducer);
        UUID again = postUpload(account, null, "report.txt", content).documentId;

        assertThat(again).as("same tenant and same content hash").isEqualTo(first);
        assertThat(documentCount(account)).isEqualTo(1);
        Mockito.verify(ingestionProducer, Mockito.never()).publish(Mockito.any());
    }

    /** Audit 4 b: a SHA-256 (or equivalent) is not stored per document. */
    @Test
    void contentHashIsStoredPerDocument() {
        List<String> columns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'documents' "
                + "and (column_name like '%hash%' or column_name like '%sha%' or column_name like '%checksum%')",
            String.class);

        assertThat(columns).as("documents must carry a content hash column").isNotEmpty();
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
        assertThat(hits).as("retrieval hits for one logical document").hasSize((int) distinctContents);
    }

    private record UploadResult(int status, UUID documentId) {}

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
        return new UploadResult(result.getResponse().getStatus(), id);
    }

    private int documentCount(Account account) {
        return jdbc.queryForObject(
            "select count(*) from documents where tenant_id = ? and deleted_at is null", Integer.class, account.tenant().getId());
    }

    private void awaitAllTerminal(Account account) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
            assertThat(jdbc.queryForObject(
                "select count(*) from documents where tenant_id = ? and deleted_at is null and status in ('PENDING','PROCESSING')",
                Integer.class, account.tenant().getId())).isZero());
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
