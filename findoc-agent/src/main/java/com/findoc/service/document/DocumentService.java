package com.findoc.service.document;

import com.findoc.dto.response.DocumentResponse;
import com.findoc.entity.Document;
import com.findoc.entity.DocumentSource;
import com.findoc.entity.DocumentChunk;
import com.findoc.entity.User;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentSourceRepository;
import com.findoc.repository.UserRepository;
import com.findoc.messaging.IngestionJob;
import com.findoc.messaging.IngestionProducer;
import com.findoc.util.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class DocumentService {
    private static final int MAX_DOCUMENTS_PER_LIST = 100;

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentSourceRepository documentSourceRepository;
    private final UserRepository userRepository;
    private final IngestionProducer ingestionProducer;

    public DocumentService(DocumentRepository documentRepository,
                          DocumentChunkRepository documentChunkRepository,
                          DocumentSourceRepository documentSourceRepository,
                          UserRepository userRepository,
                          IngestionProducer ingestionProducer) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.documentSourceRepository = documentSourceRepository;
        this.userRepository = userRepository;
        this.ingestionProducer = ingestionProducer;
    }

    @Transactional
    public DocumentResponse upload(MultipartFile file) throws IOException {
        String type = file.getContentType();
        if (!"application/pdf".equals(type) && !"text/plain".equals(type)) {
            throw new IllegalArgumentException("Only PDF and text files are supported");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }

        User user = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(TenantContext.userId(), TenantContext.tenantId())
            .orElseThrow(() -> new NoSuchElementException("User not found"));

        Document document = new Document(user.getTenant(), user, file.getOriginalFilename(), type);
        Document saved = documentRepository.save(document);
        documentSourceRepository.save(new DocumentSource(saved, user.getTenant(), file.getBytes()));
        ingestionProducer.publish(new IngestionJob(saved.getId(), TenantContext.tenantId(), TenantContext.userId()));
        return response(saved, saved.getStatus().name(), 0);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list() {
        return documentRepository.findByTenantIdAndDeletedAtIsNull(
            TenantContext.tenantId(), PageRequest.of(0, MAX_DOCUMENTS_PER_LIST))
            .stream()
            .map(document -> response(document, document.getStatus().name(), chunkCount(document.getId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse status(UUID id) {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TenantContext.tenantId())
            .orElseThrow(() -> new NoSuchElementException("Document not found"));
        return response(document, document.getStatus().name(), chunkCount(document.getId()));
    }

    @Transactional(readOnly = true)
    public byte[] download(UUID id) {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TenantContext.tenantId())
            .orElseThrow(() -> new NoSuchElementException("Document not found"));
        DocumentSource source = documentSourceRepository.findByDocumentIdAndTenantId(document.getId(), TenantContext.tenantId())
            .orElseThrow(() -> new IllegalStateException("Document source not found"));
        return source.getContent();
    }

    @Transactional
    public void delete(UUID id) {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TenantContext.tenantId())
            .orElseThrow(() -> new NoSuchElementException("Document not found"));
        document.setDeletedAt(Instant.now());
        documentRepository.save(document);
    }

    @Transactional(readOnly = true)
    public List<String> chunks(UUID id) {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TenantContext.tenantId())
            .orElseThrow(() -> new NoSuchElementException("Document not found"));
        return documentChunkRepository.findByDocumentIdAndTenantIdOrderByChunkIndexAsc(document.getId(), TenantContext.tenantId())
            .stream()
            .map(DocumentChunk::getContent)
            .toList();
    }

    private int chunkCount(UUID documentId) {
        return Math.toIntExact(documentChunkRepository.countByDocumentIdAndTenantId(documentId, TenantContext.tenantId()));
    }

    private DocumentResponse response(Document document, String status, int chunkCount) {
        return new DocumentResponse(document.getId(), document.getFilename(), document.getFileType(), status, chunkCount, document.getCreatedAt());
    }
}
