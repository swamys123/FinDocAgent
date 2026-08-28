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
import com.findoc.messaging.IngestionMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final KafkaTemplate<String, IngestionMessage> kafkaTemplate;
    private final Path uploadDirectory;
    private final String ingestionTopic;

    public DocumentService(ChunkingService chunkingService,
                          DocumentRepository documentRepository,
                          DocumentChunkRepository documentChunkRepository,
                          UserRepository userRepository,
                          KafkaTemplate<String, IngestionMessage> kafkaTemplate,
                          @Value("${ingestion.upload-dir:${java.io.tmpdir}/findoc-uploads}") String uploadDirectory,
                          @Value("${ingestion.kafka.topic:findoc.ingestion}") String ingestionTopic) {
        this.chunkingService = chunkingService;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.userRepository = userRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.uploadDirectory = Path.of(uploadDirectory);
        this.ingestionTopic = ingestionTopic;
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

        User user = userRepository.findById(TenantContext.userId())
            .orElseThrow(() -> new NoSuchElementException("User not found"));
        if (!user.getTenant().getId().equals(TenantContext.tenantId())) {
            throw new IllegalArgumentException("User does not belong to the current tenant");
        }

        Document document = new Document(user.getTenant(), user, file.getOriginalFilename(), type);
        document.setStatus(Document.Status.PENDING);
        Files.createDirectories(uploadDirectory);
        Path sourcePath = uploadDirectory.resolve(UUID.randomUUID() + "-" + safeFilename(file.getOriginalFilename()));
        Files.write(sourcePath, file.getBytes());
        document.setSourcePath(sourcePath.toString());
        Document saved = documentRepository.save(document);
        kafkaTemplate.send(ingestionTopic, saved.getId().toString(), new IngestionMessage(
            saved.getId(), TenantContext.tenantId(), TenantContext.userId(), sourcePath.toString(), type, 1));
        return response(saved, saved.getStatus().name(), 0);
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

    private String safeFilename(String filename) {
        return Path.of(filename == null ? "upload" : filename).getFileName().toString();
    }
}
