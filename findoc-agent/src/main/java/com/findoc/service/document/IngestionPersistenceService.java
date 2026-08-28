package com.findoc.service.document;

import com.findoc.entity.Document;
import com.findoc.entity.DocumentChunk;
import com.findoc.messaging.IngestionMessage;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

@Service
public class IngestionPersistenceService {
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final PdfExtractionService extractionService;
    private final ChunkingService chunkingService;

    public IngestionPersistenceService(DocumentRepository documentRepository, DocumentChunkRepository chunkRepository,
                                       PdfExtractionService extractionService, ChunkingService chunkingService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.extractionService = extractionService;
        this.chunkingService = chunkingService;
    }

    @Transactional
    public void process(IngestionMessage message) throws Exception {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(message.documentId(), message.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("Document not found for ingestion"));
        document.markProcessing();
        var chunks = chunkingService.chunk(extractionService.extract(Path.of(message.filePath()), message.fileType()));
        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            DocumentChunk chunk = new DocumentChunk(document, document.getTenant(), index, content);
            chunk.setTokenCount(content.isBlank() ? 0 : content.split("\\s+").length);
            chunkRepository.save(chunk);
        }
        document.markReady();
        documentRepository.save(document);
    }
}