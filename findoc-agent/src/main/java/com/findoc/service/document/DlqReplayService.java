package com.findoc.service.document;

import com.findoc.messaging.IngestionJob;
import com.findoc.messaging.IngestionOutbox;
import com.findoc.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class DlqReplayService {
    private final DocumentRepository documentRepository;
    private final IngestionOutbox ingestionOutbox;

    public DlqReplayService(DocumentRepository documentRepository, IngestionOutbox ingestionOutbox) {
        this.documentRepository = documentRepository;
        this.ingestionOutbox = ingestionOutbox;
    }

    @Transactional
    public void replay(UUID documentId, UUID tenantId) {
        var document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Document not found"));
        UUID userId = document.getUser().getId();

        if (documentRepository.queueFailedDocumentForReplay(documentId, tenantId) == 1) {
            ingestionOutbox.enqueue(new IngestionJob(documentId, tenantId, userId));
            return;
        }

        String currentStatus = documentRepository.findCurrentStatus(documentId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Document not found"));
        if ("PENDING".equals(currentStatus)) {
            return;
        }
        throw new ResponseStatusException(CONFLICT, "Document cannot be replayed from status " + currentStatus);
    }
}
