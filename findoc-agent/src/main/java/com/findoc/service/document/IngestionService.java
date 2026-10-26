package com.findoc.service.document;

import com.findoc.entity.Document;
import com.findoc.messaging.IngestionJob;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentSourceRepository;
import com.findoc.repository.IngestionProgressRepository;
import com.findoc.service.embedding.EmbeddingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

@Service
public class IngestionService {
    private final DocumentRepository documentRepository;
    private final DocumentSourceRepository sourceRepository;
    private final DocumentTextExtractor textExtractor;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final IngestionClaimService ingestionClaimService;
    private final IngestionProgressService ingestionProgressService;
    private final IngestionCompletionService ingestionCompletionService;

    public IngestionService(DocumentRepository documentRepository,
                            DocumentSourceRepository sourceRepository,
                            DocumentTextExtractor textExtractor,
                            ChunkingService chunkingService,
                            EmbeddingService embeddingService,
                            IngestionClaimService ingestionClaimService,
                            IngestionProgressService ingestionProgressService,
                            IngestionCompletionService ingestionCompletionService) {
        this.documentRepository = documentRepository;
        this.sourceRepository = sourceRepository;
        this.textExtractor = textExtractor;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.ingestionClaimService = ingestionClaimService;
        this.ingestionProgressService = ingestionProgressService;
        this.ingestionCompletionService = ingestionCompletionService;
    }

    public void ingest(IngestionJob job) throws Exception {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(job.documentId(), job.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Document not found for tenant"));
        if (!document.getUser().getId().equals(job.userId())) {
            throw new IllegalArgumentException("Ingestion user does not own document");
        }
        if (ingestionClaimService.claim(job.documentId(), job.tenantId()) == 0) {
            String currentStatus = documentRepository.findCurrentStatus(job.documentId(), job.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Document not found for tenant"));
            if (Document.Status.READY.name().equals(currentStatus)) {
                return;
            }
            throw new IllegalStateException("Document cannot be claimed for ingestion from status " + currentStatus);
        }

        try {
            var source = sourceRepository.findByDocumentIdAndTenantId(job.documentId(), job.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Document source not found"));
            var extraction = textExtractor.extract(document.getFileType(), source.getContent());
            if (extraction.text() == null || extraction.text().isBlank()) {
                throw new IllegalArgumentException("Document contains no extractable text");
            }
            var chunks = chunkingService.chunk(extraction.text());
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("Document produced no chunks");
            }
            Map<Integer, IngestionProgressRepository.StagedChunk> stagedChunks = new HashMap<>();
            for (IngestionProgressRepository.StagedChunk stagedChunk
                    : ingestionProgressService.load(job.documentId(), job.tenantId())) {
                stagedChunks.put(stagedChunk.chunkIndex(), stagedChunk);
            }

            List<IngestionProgressRepository.StagedChunk> preparedChunks = new ArrayList<>(chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                String content = chunks.get(index);
                int tokenCount = content.split("\\s+").length;
                IngestionProgressRepository.StagedChunk stagedChunk = stagedChunks.get(index);
                if (stagedChunk != null && stagedChunk.content().equals(content)) {
                    preparedChunks.add(stagedChunk);
                    continue;
                }

                String embedding = vectorLiteral(embeddingService.embed(content));
                ingestionProgressService.save(job.documentId(), job.tenantId(), index, content, tokenCount, embedding);
                preparedChunks.add(new IngestionProgressRepository.StagedChunk(index, content, tokenCount, embedding));
            }
            ingestionCompletionService.complete(job, preparedChunks, extraction.pageCount());
        } catch (Exception failure) {
            try {
                ingestionClaimService.recordFailure(job.documentId(), job.tenantId(), failure.getMessage(), false);
            } catch (RuntimeException stateFailure) {
                failure.addSuppressed(stateFailure);
            }
            throw failure;
        }
    }

    public void recordFailure(IngestionJob job, String message, boolean exhausted) {
        if (job != null) {
            ingestionClaimService.recordFailure(job.documentId(), job.tenantId(), message, exhausted);
        }
    }

    public int retryCount(IngestionJob job) {
        return documentRepository.findRetryCount(job.documentId(), job.tenantId())
            .orElseThrow(() -> new IllegalStateException("Document not found for tenant while reading retry count"));
    }

    private String vectorLiteral(float[] embedding) {
        StringJoiner values = new StringJoiner(",", "[", "]");
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding contains a non-finite value");
            }
            values.add(Float.toString(value));
        }
        return values.toString();
    }
}