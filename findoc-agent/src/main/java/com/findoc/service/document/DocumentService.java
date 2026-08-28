package com.findoc.service.document;

import com.findoc.dto.response.DocumentResponse;
import com.findoc.util.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentService {
    private final ChunkingService chunkingService;
    private final Map<UUID, StoredDocument> documents = new ConcurrentHashMap<>();

    public DocumentService(ChunkingService chunkingService) { this.chunkingService = chunkingService; }

    public DocumentResponse upload(MultipartFile file) throws IOException {
        String type = file.getContentType();
        if (!"application/pdf".equals(type) && !"text/plain".equals(type)) throw new IllegalArgumentException("Only PDF and text files are supported");
        if (file.isEmpty()) throw new IllegalArgumentException("File must not be empty");
        UUID id = UUID.randomUUID();
        String text = "text/plain".equals(type) ? file.getResource().getContentAsString(java.nio.charset.StandardCharsets.UTF_8) : file.getOriginalFilename();
        List<String> chunks = chunkingService.chunk(text == null ? "" : text);
        StoredDocument document = new StoredDocument(id, TenantContext.tenantId(), file.getOriginalFilename(), type, chunks, Instant.now());
        documents.put(id, document);
        return response(document, "READY");
    }

    public List<DocumentResponse> list() { return documents.values().stream().filter(d -> d.tenantId().equals(TenantContext.tenantId())).map(d -> response(d, "READY")).toList(); }
    public DocumentResponse status(UUID id) { return response(owned(id), "READY"); }
    public void delete(UUID id) { documents.remove(owned(id).id()); }
    public List<String> chunks(UUID id) { return owned(id).chunks(); }

    private StoredDocument owned(UUID id) {
        StoredDocument document = documents.get(id);
        if (document == null || !document.tenantId().equals(TenantContext.tenantId())) throw new NoSuchElementException("Document not found");
        return document;
    }
    private DocumentResponse response(StoredDocument d, String status) { return new DocumentResponse(d.id(), d.filename(), d.fileType(), status, d.chunks().size(), d.createdAt()); }
    private record StoredDocument(UUID id, UUID tenantId, String filename, String fileType, List<String> chunks, Instant createdAt) {}
}
