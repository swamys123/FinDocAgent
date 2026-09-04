package com.findoc.service.document;

import com.findoc.messaging.IngestionMessage;
import org.springframework.stereotype.Service;

@Service
public class IngestionPipeline {
    private final IngestionPersistenceService persistenceService;
    private final IngestionFailureService failureService;

    public IngestionPipeline(IngestionPersistenceService persistenceService, IngestionFailureService failureService) {
        this.persistenceService = persistenceService;
        this.failureService = failureService;
    }

    public void process(IngestionMessage message) throws Exception {
        try {
            persistenceService.process(message);
        } catch (Exception exception) {
            failureService.markFailed(message, exception);
            throw exception;
        }
    }
}