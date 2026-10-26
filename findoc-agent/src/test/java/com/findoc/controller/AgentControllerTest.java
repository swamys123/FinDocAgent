package com.findoc.controller;

import com.findoc.config.SecurityConfig;
import com.findoc.dto.response.AgentResponse;
import com.findoc.dto.response.AgentSessionResponse;
import com.findoc.dto.response.AgentTraceResponse;
import com.findoc.dto.response.AgentTraceStepResponse;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.findoc.dto.response.DocumentComparisonResponse;
import com.findoc.service.agent.AgentService;
import com.findoc.service.idempotency.IdempotencyExecutor;
import com.findoc.service.idempotency.IdempotencyResult;
import com.findoc.service.idempotency.RequestFingerprint;
import com.findoc.service.auth.JwtService;
import com.findoc.util.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
class AgentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentService agentService;

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
        when(requestFingerprint.json(anyString(), any())).thenReturn("fingerprint");
        doAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(5);
            return new IdempotencyResult<>(HttpStatus.OK, operation.get(), false);
        }).when(idempotencyExecutor).execute(
            eq(tenantId), nullable(String.class), anyString(), eq(HttpStatus.OK), any(), any());
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void queryReturnsAgentAnswer() throws Exception {
        UUID queryId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(agentService.query(any())).thenReturn(new AgentResponse(queryId, sessionId, "answer", "FACTUAL", List.of(), List.of("classify_intent"), 0.9));

        mockMvc.perform(post("/api/v1/agent/query").with(user("demo"))
                .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\":\"What is the revenue?\",\"documentIds\":[],\"sessionId\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queryId").value(queryId.toString()))
            .andExpect(jsonPath("$.answer").value("answer"))
            .andExpect(jsonPath("$.confidence").value(0.9));

        verify(agentService).query(any());
    }

    @Test
    void queryReplaysStoredResponseWhenIdempotencyKeyIsPresent() throws Exception {
        UUID queryId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AgentResponse response = new AgentResponse(queryId, sessionId, "answer", "FACTUAL", List.of(), List.of("classify_intent"), 0.9);
        org.mockito.Mockito.doReturn(new IdempotencyResult<>(HttpStatus.OK, response, true))
            .when(idempotencyExecutor).execute(
                eq(tenantId), eq("request-1"), eq("fingerprint"), eq(HttpStatus.OK),
                eq(AgentResponse.class), any());

        mockMvc.perform(post("/api/v1/agent/query").with(user("demo"))
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"What is the revenue?\",\"documentIds\":[],\"sessionId\":null}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.queryId").value(queryId.toString()));

        verify(agentService, never()).query(any());
    }

    @Test
    void compareReplaysStoredResponseWhenIdempotencyKeyIsPresent() throws Exception {
        UUID queryId = UUID.randomUUID();
        DocumentComparisonResponse response =
            new DocumentComparisonResponse(queryId, List.of("same"), List.of("different"), "summary", List.of(), List.of());
        org.mockito.Mockito.doReturn(new IdempotencyResult<>(HttpStatus.OK, response, true))
            .when(idempotencyExecutor).execute(
                eq(tenantId), eq("compare-1"), eq("fingerprint"), eq(HttpStatus.OK),
                eq(DocumentComparisonResponse.class), any());

        mockMvc.perform(post("/api/v1/agent/compare").with(user("demo"))
                .header("Idempotency-Key", "compare-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIdA\":\"%s\",\"documentIdB\":\"%s\",\"aspect\":\"pricing\"}"
                    .formatted(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.queryId").value(queryId.toString()));

        verify(agentService, never()).compare(any());
    }

    @Test
    void compareReturnsComparison() throws Exception {
        UUID queryId = UUID.randomUUID();
        when(agentService.compare(any())).thenReturn(new DocumentComparisonResponse(queryId, List.of("same"), List.of("different"), "summary", List.of(), List.of()));

        mockMvc.perform(post("/api/v1/agent/compare").with(user("demo"))
                .contentType(MediaType.APPLICATION_JSON)
            .content("{\"documentIdA\":\"%s\",\"documentIdB\":\"%s\",\"aspect\":\"pricing\"}".formatted(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queryId").value(queryId.toString()))
            .andExpect(jsonPath("$.summary").value("summary"));

        verify(agentService).compare(any());
    }

    @Test
    void sessionHistoryReturnsMessages() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(agentService.sessionHistory(sessionId)).thenReturn(new AgentSessionResponse(sessionId, List.of()));

        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}", sessionId).with(user("demo")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
            .andExpect(jsonPath("$.messages").isArray());
    }

    @Test
    void explainReturnsTrace() throws Exception {
        UUID queryId = UUID.randomUUID();
        when(agentService.explain(queryId)).thenReturn(new AgentTraceResponse(queryId, "What is the revenue?", "FACTUAL",
            List.of(new AgentTraceStepResponse(1, "classify_intent",
                JsonNodeFactory.instance.objectNode().put("query", "What is the revenue?"),
                JsonNodeFactory.instance.objectNode().put("intent", "FACTUAL"), 48)), 42));

        mockMvc.perform(get("/api/v1/agent/explain/{queryId}", queryId).with(user("demo")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queryId").value(queryId.toString()))
            .andExpect(jsonPath("$.fullTrace[0].step").value(1))
            .andExpect(jsonPath("$.fullTrace[0].tool").value("classify_intent"))
            .andExpect(jsonPath("$.fullTrace[0].input.query").value("What is the revenue?"))
            .andExpect(jsonPath("$.fullTrace[0].output.intent").value("FACTUAL"))
            .andExpect(jsonPath("$.fullTrace[0].durationMs").value(48));
    }

    @Test
    void recentTracesReturnsSummaries() throws Exception {
        UUID queryId = UUID.randomUUID();
        when(agentService.recentTraces()).thenReturn(List.of(
            new com.findoc.dto.response.QueryTraceSummaryResponse(queryId, "What is the revenue?", "FACTUAL", 42,
                java.time.Instant.parse("2026-09-20T00:00:00Z"))));

        mockMvc.perform(get("/api/v1/agent/traces/recent").with(user("demo")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].queryId").value(queryId.toString()))
            .andExpect(jsonPath("$[0].query").value("What is the revenue?"))
            .andExpect(jsonPath("$[0].intent").value("FACTUAL"))
            .andExpect(jsonPath("$[0].durationMs").value(42));
    }

    @Test
    void protectedRecentTracesRouteRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/agent/traces/recent"))
            .andExpect(status().isForbidden());

        verify(agentService, never()).recentTraces();
    }

    @Test
    void queryRejectsBlankQuery() throws Exception {
        mockMvc.perform(post("/api/v1/agent/query").with(user("demo"))
                .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\":\"\",\"documentIds\":[],\"sessionId\":null}"))
            .andExpect(status().isBadRequest());

        verify(agentService, never()).query(any());
    }

    @Test
    void protectedAgentRouteRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/agent/explain/{queryId}", UUID.randomUUID()))
            .andExpect(status().isForbidden());

        verify(agentService, never()).explain(any());
    }
}
