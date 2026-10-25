package com.findoc.service.document;

import com.findoc.entity.Document;
import com.findoc.entity.DocumentSource;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.messaging.IngestionJob;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentSourceRepository;
import com.findoc.service.embedding.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceTest {
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentSourceRepository sourceRepository = mock(DocumentSourceRepository.class);
    private final DocumentTextExtractor textExtractor = mock(DocumentTextExtractor.class);
    private final ChunkingService chunkingService = mock(ChunkingService.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final IngestionClaimService ingestionClaimService = mock(IngestionClaimService.class);
    private final IngestionProgressService ingestionProgressService = mock(IngestionProgressService.class);
    private final IngestionCompletionService ingestionCompletionService = mock(IngestionCompletionService.class);
    private final IngestionService service = new IngestionService(
        documentRepository, sourceRepository, textExtractor, chunkingService, embeddingService,
        ingestionClaimService, ingestionProgressService, ingestionCompletionService);

    @Test
    void ingestsChunksAndPersistsPageCount() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Tenant tenant = mock(Tenant.class);
        User user = mock(User.class);
        Document document = new Document(tenant, user, "report.pdf", "application/pdf");
        DocumentSource source = mock(DocumentSource.class);
        IngestionJob job = new IngestionJob(documentId, tenantId, userId);
        when(user.getId()).thenReturn(userId);
        when(ingestionClaimService.claim(documentId, tenantId)).thenReturn(1);
        when(ingestionProgressService.load(documentId, tenantId)).thenReturn(List.of());
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(sourceRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(source));
        when(source.getContent()).thenReturn(new byte[] {1});
        when(textExtractor.extract("application/pdf", new byte[] {1}))
            .thenReturn(new DocumentTextExtractor.Extraction("alpha beta", 4));
        when(chunkingService.chunk("alpha beta")).thenReturn(List.of("alpha", "beta"));
        when(embeddingService.embed(any(String.class))).thenReturn(new float[] {0.0f});

        service.ingest(job);

        verify(ingestionClaimService).claim(documentId, tenantId);
        verify(ingestionProgressService).save(documentId, tenantId, 0, "alpha", 1, "[0.0]");
        verify(ingestionProgressService).save(documentId, tenantId, 1, "beta", 1, "[0.0]");
        verify(ingestionCompletionService).complete(org.mockito.ArgumentMatchers.eq(job), any(), org.mockito.ArgumentMatchers.eq(4));
    }

    @Test
    void rejectsJobForDifferentDocumentOwner() {
        UUID userId = UUID.randomUUID();
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(UUID.randomUUID());
        Document document = new Document(mock(Tenant.class), owner, "report.pdf", "application/pdf");
        IngestionJob job = new IngestionJob(UUID.randomUUID(), UUID.randomUUID(), userId);

        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(job.documentId(), job.tenantId()))
            .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.ingest(job))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Ingestion user does not own document");
        verify(ingestionClaimService, never()).claim(any(), any());
        verify(sourceRepository, never()).findByDocumentIdAndTenantId(any(), any());
    }

    @Test
    void treatsReadyDocumentAsNoOpWhenAtomicClaimFindsNoEligibleStatus() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        Document document = new Document(mock(Tenant.class), user, "report.pdf", "application/pdf");
        IngestionJob job = new IngestionJob(documentId, tenantId, userId);
        when(user.getId()).thenReturn(userId);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(ingestionClaimService.claim(documentId, tenantId)).thenReturn(0);
        when(documentRepository.findCurrentStatus(documentId, tenantId)).thenReturn(Optional.of("READY"));

        service.ingest(job);

        verify(sourceRepository, never()).findByDocumentIdAndTenantId(any(), any());
        verify(embeddingService, never()).embed(any());
        verify(ingestionCompletionService, never()).complete(any(), any(), any());
    }

    @Test
    void rejectsOtherStatusesWhenAtomicClaimFindsNoEligibleStatus() {
        UUID documentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        Document document = new Document(mock(Tenant.class), user, "report.pdf", "application/pdf");
        IngestionJob job = new IngestionJob(documentId, tenantId, userId);
        when(user.getId()).thenReturn(userId);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(ingestionClaimService.claim(documentId, tenantId)).thenReturn(0);
        when(documentRepository.findCurrentStatus(documentId, tenantId)).thenReturn(Optional.of("PROCESSING"));

        assertThatThrownBy(() -> service.ingest(job))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Document cannot be claimed for ingestion from status PROCESSING");
        verify(sourceRepository, never()).findByDocumentIdAndTenantId(any(), any());
    }

    @Test
    void recordsFailureWithoutReplacingExistingChunksWhenEmbeddingFails() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        Document document = new Document(mock(Tenant.class), user, "report.txt", "text/plain");
        DocumentSource source = mock(DocumentSource.class);
        IngestionJob job = new IngestionJob(documentId, tenantId, userId);
        when(user.getId()).thenReturn(userId);
        when(ingestionClaimService.claim(documentId, tenantId)).thenReturn(1);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(sourceRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(source));
        when(source.getContent()).thenReturn(new byte[] {1});
        when(textExtractor.extract("text/plain", new byte[] {1}))
            .thenReturn(new DocumentTextExtractor.Extraction("alpha", null));
        when(chunkingService.chunk("alpha")).thenReturn(List.of("alpha"));
        doThrow(new IllegalStateException("embedding unavailable")).when(embeddingService).embed("alpha");

        assertThatThrownBy(() -> service.ingest(job))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("embedding unavailable");

        verify(ingestionCompletionService, never()).complete(any(), any(), any());
        verify(ingestionClaimService).recordFailure(documentId, tenantId, "embedding unavailable", false);
    }

    @Test
    void stagesFloatEmbeddingLiteralsForAtomicCompletion() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Tenant tenant = mock(Tenant.class);
        User user = mock(User.class);
        Document document = new Document(tenant, user, "report.pdf", "application/pdf");
        DocumentSource source = mock(DocumentSource.class);
        IngestionJob job = new IngestionJob(documentId, tenantId, userId);

        when(user.getId()).thenReturn(userId);
        when(ingestionClaimService.claim(documentId, tenantId)).thenReturn(1);
        when(ingestionProgressService.load(documentId, tenantId)).thenReturn(List.of());
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(sourceRepository.findByDocumentIdAndTenantId(documentId, tenantId))
            .thenReturn(Optional.of(source));
        when(source.getContent()).thenReturn(new byte[] {1});
        when(textExtractor.extract("application/pdf", new byte[] {1}))
            .thenReturn(new DocumentTextExtractor.Extraction("alpha beta", 1));
        when(chunkingService.chunk("alpha beta")).thenReturn(List.of("alpha", "beta"));

        float[] alphaEmbedding = new float[] {0.1f, 0.2f, 0.3f};
        float[] betaEmbedding = new float[] {0.4f, 0.5f, 0.6f};
        when(embeddingService.embed("alpha")).thenReturn(alphaEmbedding);
        when(embeddingService.embed("beta")).thenReturn(betaEmbedding);

        service.ingest(job);

        verify(ingestionProgressService).save(documentId, tenantId, 0, "alpha", 1, "[0.1,0.2,0.3]");
        verify(ingestionProgressService).save(documentId, tenantId, 1, "beta", 1, "[0.4,0.5,0.6]");
        verify(ingestionCompletionService).complete(
            org.mockito.ArgumentMatchers.eq(job),
            org.mockito.ArgumentMatchers.argThat(chunks -> chunks.size() == 2
                && chunks.get(0).embedding().equals("[0.1,0.2,0.3]")
                && chunks.get(1).embedding().equals("[0.4,0.5,0.6]")),
            org.mockito.ArgumentMatchers.eq(1));
    }
}
