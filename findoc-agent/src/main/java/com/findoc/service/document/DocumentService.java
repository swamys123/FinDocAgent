package com.findoc.service.document;

import com.findoc.dto.response.DocumentResponse;
import com.findoc.entity.Document;
import com.findoc.entity.DocumentSource;
import com.findoc.entity.DocumentChunk;
import com.findoc.entity.User;
import com.findoc.messaging.IngestionOutbox;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentSourceRepository;
import com.findoc.repository.UserRepository;
import com.findoc.repository.DocumentDeduplicationRepository;
import com.findoc.messaging.IngestionJob;
import com.findoc.util.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
    private final IngestionOutbox ingestionOutbox;
    private final DocumentDeduplicationRepository deduplicationRepository;

    public DocumentService(DocumentRepository documentRepository,
                          DocumentChunkRepository documentChunkRepository,
                          DocumentSourceRepository documentSourceRepository,
                          UserRepository userRepository,
                          IngestionOutbox ingestionOutbox,
                          DocumentDeduplicationRepository deduplicationRepository) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.documentSourceRepository = documentSourceRepository;
        this.userRepository = userRepository;
        this.ingestionOutbox = ingestionOutbox;
        this.deduplicationRepository = deduplicationRepository;
    }

    @Transactional
    public DocumentResponse upload(MultipartFile file) throws IOException {
        return upload(file, file.getBytes());
    }

    @Transactional
    public DocumentResponse upload(MultipartFile file, byte[] content) {
        String type = file.getContentType();
        if (!"application/pdf".equals(type) && !"text/plain".equals(type)) {
            throw new IllegalArgumentException("Only PDF and text files are supported");
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("File must not be empty");
        }

        User user = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(TenantContext.userId(), TenantContext.tenantId())
            .orElseThrow(() -> new NoSuchElementException("User not found"));

        UUID tenantId = TenantContext.tenantId();
        String contentHash = sha256(content);
        deduplicationRepository.lock(tenantId, contentHash);
        var duplicate = documentRepository.findByTenantIdAndContentHashAndDeletedAtIsNull(tenantId, contentHash);
        if (duplicate.isPresent()) {
            Document existing = duplicate.get();
            String status = existing.getStatus().name();
            String errorMessage = existing.getErrorMessage();
            if (documentRepository.queueFailedDocumentForReplay(existing.getId(), tenantId) == 1) {
                ingestionOutbox.enqueue(new IngestionJob(existing.getId(), tenantId, existing.getUser().getId()));
                status = Document.Status.PENDING.name();
                errorMessage = null;
            }
            return new DocumentResponse(existing.getId(), existing.getFilename(), existing.getFileType(), status,
                chunkCount(existing.getId()), existing.getCreatedAt(), true, errorMessage);
        }

        Document document = new Document(user.getTenant(), user, file.getOriginalFilename(), type, contentHash);
        Document saved = documentRepository.save(document);
        documentSourceRepository.save(new DocumentSource(saved, user.getTenant(), content));
        IngestionJob job = new IngestionJob(saved.getId(), tenantId, TenantContext.userId());
        ingestionOutbox.enqueue(job);
        return response(saved, saved.getStatus().name(), 0, false);
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
        documentChunkRepository.softDeleteByDocumentIdAndTenantId(document.getId(), TenantContext.tenantId(), Instant.now());
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
        return response(document, status, chunkCount, false);
    }

    private DocumentResponse response(Document document, String status, int chunkCount, boolean duplicate) {
        return new DocumentResponse(document.getId(), document.getFilename(), document.getFileType(), status,
            chunkCount, document.getCreatedAt(), duplicate, document.getErrorMessage());
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

}
