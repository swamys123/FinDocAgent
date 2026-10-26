package com.findoc.messaging;

import com.findoc.repository.StaleIngestionRepository;
import com.findoc.service.document.StaleIngestionRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StaleIngestionRecovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaleIngestionRecovery.class);

    private final StaleIngestionRepository staleIngestionRepository;
    private final StaleIngestionRecoveryService recoveryService;
    private final long leaseMillis;
    private final int batchSize;

    public StaleIngestionRecovery(StaleIngestionRepository staleIngestionRepository,
                                  StaleIngestionRecoveryService recoveryService,
                                  @Value("${findoc.ingestion.processing-lease-ms:300000}") long leaseMillis,
                                  @Value("${findoc.ingestion.recovery.batch-size:20}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Stale ingestion recovery batch size must be positive");
        }
        this.staleIngestionRepository = staleIngestionRepository;
        this.recoveryService = recoveryService;
        this.leaseMillis = leaseMillis;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${findoc.ingestion.recovery.poll-interval-ms:30000}")
    public void recoverStaleIngestions() {
        for (IngestionJob job : staleIngestionRepository.findStaleJobs(leaseMillis, batchSize)) {
            try {
                recoveryService.requeueIfStale(job);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not enqueue recovery for stale ingestion document {}", job.documentId(), exception);
            }
        }
    }
}
