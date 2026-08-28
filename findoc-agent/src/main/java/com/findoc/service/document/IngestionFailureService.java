package com.findoc.service.document;

import com.findoc.messaging.IngestionMessage;
import com.findoc.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionFailureService {
    private final DocumentRepository documentRepository;

    public IngestionFailureService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(IngestionMessage message, Exception exception) {
        documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(message.documentId(), message.tenantId())
            .ifPresent(document -> {
                document.markFailed(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
                documentRepository.save(document);
            });
    }
}