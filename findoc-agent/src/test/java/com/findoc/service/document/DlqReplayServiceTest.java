package com.findoc.service.document;

import com.findoc.entity.Document;
import com.findoc.entity.User;
import com.findoc.messaging.IngestionJob;
import com.findoc.messaging.IngestionOutbox;
import com.findoc.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DlqReplayServiceTest {
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final IngestionOutbox ingestionOutbox = mock(IngestionOutbox.class);
    private final DlqReplayService service = new DlqReplayService(documentRepository, ingestionOutbox);
    private final UUID documentId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void failedDocumentTransitionsAndEnqueuesOriginalTenantAndOwnerOnce() {
        Document document = documentWithOwner();
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(documentRepository.queueFailedDocumentForReplay(documentId, tenantId)).thenReturn(1, 0);
        when(documentRepository.findCurrentStatus(documentId, tenantId)).thenReturn(Optional.of("PENDING"));

        service.replay(documentId, tenantId);
        service.replay(documentId, tenantId);

        verify(ingestionOutbox, times(1)).enqueue(new IngestionJob(documentId, tenantId, userId));
    }

    @Test
    void pendingDocumentIsAnAcceptedNoOpWithoutDuplicateEnqueue() {
        Document document = documentWithOwner();
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(documentRepository.queueFailedDocumentForReplay(documentId, tenantId)).thenReturn(0);
        when(documentRepository.findCurrentStatus(documentId, tenantId)).thenReturn(Optional.of("PENDING"));

        service.replay(documentId, tenantId);

        verify(ingestionOutbox, never()).enqueue(any());
    }

    @Test
    void processingAndReadyDocumentsCannotBeReplayed() {
        Document document = documentWithOwner();
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.of(document));
        when(documentRepository.queueFailedDocumentForReplay(documentId, tenantId)).thenReturn(0);
        when(documentRepository.findCurrentStatus(documentId, tenantId)).thenReturn(Optional.of("PROCESSING"));

        assertThatThrownBy(() -> service.replay(documentId, tenantId))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode().value()).isEqualTo(409));

        when(documentRepository.findCurrentStatus(documentId, tenantId)).thenReturn(Optional.of("READY"));
        assertThatThrownBy(() -> service.replay(documentId, tenantId))
            .isInstanceOf(ResponseStatusException.class);
        verify(ingestionOutbox, never()).enqueue(any());
    }

    @Test
    void crossTenantOrMissingDocumentIsNotFound() {
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay(documentId, tenantId))
            .isInstanceOf(NoSuchElementException.class);

        verify(documentRepository, never()).queueFailedDocumentForReplay(any(), any());
        verify(ingestionOutbox, never()).enqueue(any());
    }

    private Document documentWithOwner() {
        Document document = mock(Document.class);
        User owner = mock(User.class);
        when(document.getUser()).thenReturn(owner);
        when(owner.getId()).thenReturn(userId);
        return document;
    }
}
