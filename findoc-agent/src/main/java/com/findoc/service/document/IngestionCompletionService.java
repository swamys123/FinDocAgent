package com.findoc.service.document;

import com.findoc.messaging.IngestionJob;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.IngestionProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class IngestionCompletionService {
    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final IngestionProgressRepository progressRepository;

    public IngestionCompletionService(DocumentChunkRepository chunkRepository,
                                     DocumentRepository documentRepository,
                                     IngestionProgressRepository progressRepository) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.progressRepository = progressRepository;
    }

    @Transactional
    public void complete(IngestionJob job, List<IngestionProgressRepository.StagedChunk> chunks, Integer pageCount) {
        for (IngestionProgressRepository.StagedChunk chunk : chunks) {
            int affectedRows = chunkRepository.upsertLiveChunk(
                job.documentId(), job.tenantId(), chunk.chunkIndex(), chunk.content(), chunk.tokenCount(),
                chunk.embedding());
            if (affectedRows != 1) {
                throw new IllegalStateException("Document was deleted or is outside tenant scope during ingestion");
            }
        }
        chunkRepository.softDeleteFromChunkIndex(job.documentId(), job.tenantId(), chunks.size(), Instant.now());
        if (documentRepository.completeIngestion(job.documentId(), job.tenantId(), pageCount) != 1) {
            throw new IllegalStateException("Document is no longer PROCESSING");
        }
        progressRepository.deleteChunks(job.documentId(), job.tenantId());
    }
}
