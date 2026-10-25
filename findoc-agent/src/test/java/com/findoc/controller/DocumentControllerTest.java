package com.findoc.controller;

import com.findoc.config.SecurityConfig;
import com.findoc.dto.response.DocumentResponse;
import com.findoc.service.auth.JwtService;
import com.findoc.service.document.DocumentService;
import com.findoc.service.idempotency.IdempotencyExecutor;
import com.findoc.service.idempotency.IdempotencyResult;
import com.findoc.service.idempotency.RequestFingerprint;
import com.findoc.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
class DocumentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private IdempotencyExecutor idempotencyExecutor;

    @MockBean
    private RequestFingerprint requestFingerprint;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(tenantId, UUID.randomUUID());
        when(requestFingerprint.upload(any(), any(), any(), any())).thenReturn("fingerprint");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void listReturnsTenantDocumentsForAuthenticatedUser() throws Exception {
        DocumentResponse response = document("report.pdf", "application/pdf");
        when(documentService.list()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/documents").with(user("demo")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].documentId").value(response.documentId().toString()))
            .andExpect(jsonPath("$[0].filename").value("report.pdf"));

        verify(documentService).list();
    }

    @Test
    void uploadReturnsAcceptedDocument() throws Exception {
        DocumentResponse response = document("notes.txt", "text/plain");
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());
        when(documentService.upload(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(multipart("/api/v1/documents/upload").file(file).with(user("demo")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.filename").value("notes.txt"));

        verify(documentService).upload(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uploadReturnsStoredResponseForIdempotentReplay() throws Exception {
        DocumentResponse response = document("notes.txt", "text/plain");
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());
        doReturn(new IdempotencyResult<>(HttpStatus.ACCEPTED, response, true))
            .when(idempotencyExecutor).execute(
                eq(tenantId), eq("upload-1"), eq("fingerprint"), eq(HttpStatus.ACCEPTED),
                eq(DocumentResponse.class), any());

        mockMvc.perform(multipart("/api/v1/documents/upload")
                .file(file)
                .header("Idempotency-Key", "upload-1")
                .with(user("demo")))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.documentId").value(response.documentId().toString()));

        verify(documentService, never()).upload(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void statusReturnsDocumentStatus() throws Exception {
        UUID documentId = UUID.randomUUID();
        DocumentResponse response = new DocumentResponse(documentId, "report.pdf", "application/pdf", "READY", 2, Instant.parse("2026-09-13T10:00:00Z"));
        when(documentService.status(documentId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/documents/{id}/status", documentId).with(user("demo")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.chunkCount").value(2));
    }

    @Test
    void downloadReturnsOriginalBytesAndMetadataHeaders() throws Exception {
        UUID documentId = UUID.randomUUID();
        DocumentResponse response = document(documentId, "report.pdf", "application/pdf");
        when(documentService.status(documentId)).thenReturn(response);
        when(documentService.download(documentId)).thenReturn("pdf-bytes".getBytes());

        mockMvc.perform(get("/api/v1/documents/{id}/download", documentId).with(user("demo")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"report.pdf\""))
            .andExpect(content().bytes("pdf-bytes".getBytes()));

        verify(documentService).status(documentId);
        verify(documentService).download(documentId);
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        UUID documentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId).with(user("demo")))
            .andExpect(status().isNoContent());

        verify(documentService).delete(documentId);
    }

    @Test
    void protectedDocumentRouteRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
            .andExpect(status().isForbidden());

        verify(documentService, never()).list();
    }

    @Test
    void uploadRequiresFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents/upload").with(user("demo")))
            .andExpect(status().isBadRequest());
    }

    private static DocumentResponse document(String filename, String fileType) {
        return document(UUID.randomUUID(), filename, fileType);
    }

    private static DocumentResponse document(UUID documentId, String filename, String fileType) {
        return new DocumentResponse(documentId, filename, fileType, "PENDING", 0, Instant.parse("2026-09-13T10:00:00Z"));
    }
}
