package com.findoc.controller;

import com.findoc.config.SecurityConfig;
import com.findoc.dto.request.AuthRequest;
import com.findoc.dto.response.AuthResponse;
import com.findoc.service.auth.AuthService;
import com.findoc.service.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    @Test
    void tokenReturnsAuthenticationResponse() throws Exception {
        UUID tenantId = UUID.randomUUID();
        AuthRequest request = new AuthRequest(tenantId, "demo@example.com", "password");
        AuthResponse response = new AuthResponse("access-token", "Bearer", 3600, tenantId);
        when(authService.authenticate(any(AuthRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"tenantId":"%s","username":"demo@example.com","password":"password"}
                    """.formatted(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(3600))
            .andExpect(jsonPath("$.tenantId").value(tenantId.toString()));

        verify(authService).authenticate(request);
    }

    @Test
    void tokenRejectsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tenantId\":null,\"username\":\"\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest());
    }
}
