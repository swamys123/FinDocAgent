package com.findoc.service.document;

import com.findoc.entity.Document;
import com.findoc.entity.DocumentChunk;
import com.findoc.messaging.IngestionJob;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentSourceRepository;
import com.findoc.service.embedding.EmbeddingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IngestionService {
    private final DocumentRepository documentRepository;
    private final DocumentSourceRepository sourceRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentTextExtractor textExtractor;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;

    public IngestionService(DocumentRepository documentRepository,
                            DocumentSourceRepository sourceRepository,
                            DocumentChunkRepository chunkRepository,
                            DocumentTextExtractor textExtractor,
                            ChunkingService chunkingService,
                            EmbeddingService embeddingService) {
        this.documentRepository = documentRepository;
        this.sourceRepository = sourceRepository;
        this.chunkRepository = chunkRepository;
        this.textExtractor = textExtractor;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
    }

    @Transactional
    public void ingest(IngestionJob job) throws Exception {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(job.documentId(), job.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Document not found for tenant"));
        if (!document.getUser().getId().equals(job.userId())) {
            throw new IllegalArgumentException("Ingestion user does not own document");
        }
        document.markProcessing();
        var source = sourceRepository.findByDocumentIdAndTenantId(job.documentId(), job.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Document source not found"));
        var extraction = textExtractor.extract(document.getFileType(), source.getContent());
        if (extraction.text() == null || extraction.text().isBlank()) {
            throw new IllegalArgumentException("Document contains no extractable text");
        }
        document.setPageCount(extraction.pageCount());

        var chunks = chunkingService.chunk(extraction.text());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Document produced no chunks");
        }
        chunkRepository.deleteByDocumentIdAndTenantId(job.documentId(), job.tenantId());
        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            var chunk = new DocumentChunk(document, document.getTenant(), index, content);
            chunk.setTokenCount(content.split("\\s+").length);
            chunk.setEmbedding(embeddingService.embed(content));
            chunkRepository.save(chunk);
        }
        document.markReady();
        documentRepository.save(document);
    }

    @Transactional
    public void recordFailure(IngestionJob job, String message, boolean exhausted) {
        documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(job.documentId(), job.tenantId())
            .ifPresent(document -> document.recordFailure(message, exhausted));
    }
}