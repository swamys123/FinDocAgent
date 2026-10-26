package com.findoc.controller;

import com.findoc.config.SecurityConfig;
import com.findoc.service.auth.JwtService;
import com.findoc.service.document.DlqReplayService;
import com.findoc.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDlqReplayController.class)
@Import(SecurityConfig.class)
class AdminDlqReplayControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DlqReplayService replayService;

    @MockBean
    private JwtService jwtService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(tenantId, UUID.randomUUID());
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void tenantAdminCanReplayDocumentInTheirTenant() throws Exception {
        mockMvc.perform(post("/api/v1/admin/dlq/{documentId}/replay", documentId)
                .with(user("tenant-admin").roles("ADMIN")))
            .andExpect(status().isAccepted());

        verify(replayService).replay(documentId, tenantId);
    }

    @Test
    void bearerAdminRoleAuthorizesUsingTenantFromTheToken() throws Exception {
        UUID tokenTenantId = UUID.randomUUID();
        String token = token(tokenTenantId, "ADMIN");

        mockMvc.perform(post("/api/v1/admin/dlq/{documentId}/replay", documentId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isAccepted());

        verify(replayService).replay(documentId, tokenTenantId);
    }

    @Test
    void regularUserCannotReplayDlqMessage() throws Exception {
        mockMvc.perform(post("/api/v1/admin/dlq/{documentId}/replay", documentId)
                .with(user("regular-user").roles("USER")))
            .andExpect(status().isForbidden());

        verify(replayService, never()).replay(documentId, tenantId);
    }

    @Test
    void bearerUserRoleCannotReplayDlqMessage() throws Exception {
        String token = token(tenantId, "USER");

        mockMvc.perform(post("/api/v1/admin/dlq/{documentId}/replay", documentId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());

        verify(replayService, never()).replay(documentId, tenantId);
    }

    @Test
    void endpointConflictIsNotConvertedToAnAuthenticationFailure() throws Exception {
        String token = token(tenantId, "ADMIN");
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Document is processing"))
            .when(replayService).replay(documentId, tenantId);

        mockMvc.perform(post("/api/v1/admin/dlq/{documentId}/replay", documentId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
    }

    @Test
    void anonymousRequestCannotReplayDlqMessage() throws Exception {
        mockMvc.perform(post("/api/v1/admin/dlq/{documentId}/replay", documentId))
            .andExpect(status().isForbidden());

        verify(replayService, never()).replay(documentId, tenantId);
    }

    private String token(UUID claimTenantId, String role) {
        JwtService issuer = new JwtService("test-secret-at-least-32-characters-long", 3600);
        String token = issuer.issue(claimTenantId, UUID.randomUUID(), "test-user", role);
        org.mockito.Mockito.when(jwtService.parse(token)).thenReturn(issuer.parse(token));
        return token;
    }
}
