package com.findoc.service.document;

import com.findoc.repository.IngestionProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class IngestionProgressService {
    private final IngestionProgressRepository progressRepository;

    public IngestionProgressService(IngestionProgressRepository progressRepository) {
        this.progressRepository = progressRepository;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<IngestionProgressRepository.StagedChunk> load(UUID documentId, UUID tenantId) {
        return progressRepository.findChunks(documentId, tenantId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(UUID documentId, UUID tenantId, int chunkIndex, String content,
                     int tokenCount, String embedding) {
        if (progressRepository.saveChunk(documentId, tenantId, chunkIndex, content, tokenCount, embedding) != 1) {
            throw new IllegalStateException("Document is no longer PROCESSING");
        }
    }
}
