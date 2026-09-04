package com.findoc.service.document;

import com.findoc.entity.Document;
import com.findoc.entity.DocumentSource;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.messaging.IngestionJob;
import com.findoc.repository.DocumentChunkRepository;
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
    private final DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
    private final DocumentTextExtractor textExtractor = mock(DocumentTextExtractor.class);
    private final ChunkingService chunkingService = mock(ChunkingService.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final IngestionService service = new IngestionService(
        documentRepository, sourceRepository, chunkRepository, textExtractor, chunkingService, embeddingService);

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
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(sourceRepository.findByDocumentIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(source));
        when(source.getContent()).thenReturn(new byte[] {1});
        when(textExtractor.extract("application/pdf", new byte[] {1}))
            .thenReturn(new DocumentTextExtractor.Extraction("alpha beta", 4));
        when(chunkingService.chunk("alpha beta")).thenReturn(List.of("alpha", "beta"));
        when(embeddingService.embed(any(String.class))).thenReturn(new float[768]);

        service.ingest(job);

        assertThat(document.getStatus()).isEqualTo(Document.Status.READY);
        assertThat(document.getPageCount()).isEqualTo(4);
        verify(chunkRepository).deleteByDocumentIdAndTenantId(documentId, tenantId);
        verify(chunkRepository, org.mockito.Mockito.times(2)).save(any());
        verify(documentRepository).save(document);
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

        assertThat(document.getStatus()).isEqualTo(Document.Status.PROCESSING);
        verify(chunkRepository).deleteByDocumentIdAndTenantId(documentId, tenantId);
        verify(documentRepository, never()).save(document);
    }
}
