package com.findoc.controller;

import com.findoc.config.SecurityConfig;
import com.findoc.dto.response.AgentResponse;
import com.findoc.dto.response.AgentSessionResponse;
import com.findoc.dto.response.AgentTraceResponse;
import com.findoc.dto.response.DocumentComparisonResponse;
import com.findoc.service.agent.AgentService;
import com.findoc.service.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
class AgentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentService agentService;

    @MockBean
    private JwtService jwtService;

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
        when(agentService.explain(queryId)).thenReturn(new AgentTraceResponse(queryId, "What is the revenue?", "FACTUAL", List.of("classify_intent"), 42));

        mockMvc.perform(get("/api/v1/agent/explain/{queryId}", queryId).with(user("demo")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queryId").value(queryId.toString()))
            .andExpect(jsonPath("$.fullTrace[0]").value("classify_intent"));
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
