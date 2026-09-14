package com.findoc.controller;

import com.findoc.config.SecurityConfig;
import com.findoc.dto.response.DocumentResponse;
import com.findoc.service.auth.JwtService;
import com.findoc.service.document.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
