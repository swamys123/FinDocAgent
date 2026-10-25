package com.findoc.service.document;

import com.findoc.messaging.IngestionJob;
import com.findoc.messaging.IngestionOutbox;
import com.findoc.repository.StaleIngestionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaleIngestionRecoveryService {
    private final StaleIngestionRepository staleIngestionRepository;
    private final IngestionOutbox ingestionOutbox;
    private final long leaseMillis;

    public StaleIngestionRecoveryService(StaleIngestionRepository staleIngestionRepository,
                                         IngestionOutbox ingestionOutbox,
                                         @Value("${findoc.ingestion.processing-lease-ms:300000}") long leaseMillis) {
        if (leaseMillis < 1) {
            throw new IllegalArgumentException("Ingestion processing lease must be positive");
        }
        this.staleIngestionRepository = staleIngestionRepository;
        this.ingestionOutbox = ingestionOutbox;
        this.leaseMillis = leaseMillis;
    }

    @Transactional
    public boolean requeueIfStale(IngestionJob job) {
        if (staleIngestionRepository.requeueIfStillStale(job, leaseMillis) != 1) {
            return false;
        }
        ingestionOutbox.enqueue(job);
        return true;
    }
}
