package com.findoc.controller;

import com.findoc.dto.response.DocumentResponse;
import com.findoc.service.document.DocumentService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }
    @PostMapping("/upload")
    public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file) throws IOException { return ResponseEntity.accepted().body(service.upload(file)); }
    @GetMapping public List<DocumentResponse> list() { return service.list(); }
    @GetMapping("/{id}/status") public DocumentResponse status(@PathVariable UUID id) { return service.status(id); }
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        DocumentResponse metadata = service.status(id);
        byte[] content = service.download(id);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(metadata.filename())
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header(HttpHeaders.CONTENT_TYPE, metadata.fileType())
            .body(content);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }
}
