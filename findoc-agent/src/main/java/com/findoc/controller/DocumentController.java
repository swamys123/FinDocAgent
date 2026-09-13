package com.findoc.controller;

import com.findoc.dto.response.DocumentResponse;
import com.findoc.service.document.DocumentService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Documents")
@SecurityRequirement(name = "bearer-jwt")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }
    @PostMapping("/upload")
    @Operation(summary = "Upload a document", responses = {
        @ApiResponse(responseCode = "202", description = "Document accepted", content = @Content(schema = @Schema(implementation = DocumentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid or missing file"),
        @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file) throws IOException { return ResponseEntity.accepted().body(service.upload(file)); }
    @GetMapping
    @Operation(summary = "List active documents", responses = {
        @ApiResponse(responseCode = "200", description = "Documents returned", content = @Content(array = @ArraySchema(schema = @Schema(implementation = DocumentResponse.class)))),
        @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public List<DocumentResponse> list() { return service.list(); }
    @GetMapping("/{id}/status")
    @Operation(summary = "Get document processing status", responses = {
        @ApiResponse(responseCode = "200", description = "Status returned", content = @Content(schema = @Schema(implementation = DocumentResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public DocumentResponse status(@Parameter(description = "Document identifier", required = true) @PathVariable UUID id) { return service.status(id); }
    @GetMapping("/{id}/download")
    @Operation(summary = "Download the original document", responses = {
        @ApiResponse(responseCode = "200", description = "Original document bytes", content = @Content(mediaType = "application/octet-stream")),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<byte[]> download(@Parameter(description = "Document identifier", required = true) @PathVariable UUID id) {
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
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document", responses = {
        @ApiResponse(responseCode = "204", description = "Document deleted"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<Void> delete(@Parameter(description = "Document identifier", required = true) @PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }
}
