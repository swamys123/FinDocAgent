package com.findoc.service.document;

import com.findoc.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IngestionClaimService {
    private final DocumentRepository documentRepository;

    public IngestionClaimService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int claim(UUID documentId, UUID tenantId) {
        return documentRepository.claimForIngestion(documentId, tenantId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID documentId, UUID tenantId, String message, boolean exhausted) {
        documentRepository.recordIngestionFailure(documentId, tenantId, message, exhausted);
    }
}
