package com.findoc.service.document;

import com.findoc.dto.response.DocumentResponse;
import com.findoc.entity.Document;
import com.findoc.entity.DocumentChunk;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.repository.DocumentChunkRepository;
import com.findoc.repository.DocumentRepository;
import com.findoc.repository.UserRepository;
import com.findoc.util.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class DocumentService {
    private static final int MAX_DOCUMENTS_PER_LIST = 100;

    private final ChunkingService chunkingService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final UserRepository userRepository;

    public DocumentService(ChunkingService chunkingService,
                          DocumentRepository documentRepository,
                          DocumentChunkRepository documentChunkRepository,
                          UserRepository userRepository) {
        this.chunkingService = chunkingService;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.userRepository = userRepository;
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

        String text = "text/plain".equals(type)
            ? new String(file.getBytes(), StandardCharsets.UTF_8)
            : file.getOriginalFilename() == null ? "" : file.getOriginalFilename();

        Document document = new Document(user.getTenant(), user, file.getOriginalFilename(), type);
        document.setStatus(Document.Status.PENDING);
        Document saved = documentRepository.save(document);

        List<String> chunks = chunkingService.chunk(text);
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = new DocumentChunk(saved, user.getTenant(), i, chunks.get(i));
            chunk.setTokenCount(chunks.get(i).split("\\s+").length);
            documentChunkRepository.save(chunk);
        }

        return response(saved, saved.getStatus().name(), chunks.size());
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list() {
        return documentRepository.findByTenantIdAndDeletedAtIsNull(
            TenantContext.tenantId(), PageRequest.of(0, MAX_DOCUMENTS_PER_LIST))
            .stream()
            .map(document -> response(document, document.getStatus().name(), documentChunkRepository.findByDocumentIdAndTenantIdOrderByChunkIndexAsc(document.getId(), TenantContext.tenantId()).size()))
            .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse status(UUID id) {
        Document document = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TenantContext.tenantId())
            .orElseThrow(() -> new NoSuchElementException("Document not found"));
        return response(document, document.getStatus().name(), documentChunkRepository.findByDocumentIdAndTenantIdOrderByChunkIndexAsc(document.getId(), TenantContext.tenantId()).size());
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

    private DocumentResponse response(Document document, String status, int chunkCount) {
        return new DocumentResponse(document.getId(), document.getFilename(), document.getFileType(), status, chunkCount, document.getCreatedAt());
    }
}
