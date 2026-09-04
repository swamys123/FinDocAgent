package com.findoc.controller;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.request.DocumentComparisonRequest;
import com.findoc.dto.response.AgentSessionResponse;
import com.findoc.dto.response.AgentResponse;
import com.findoc.dto.response.AgentTraceResponse;
import com.findoc.dto.response.DocumentComparisonResponse;
import com.findoc.service.agent.AgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service) { this.service = service; }
    @PostMapping("/query")
    public AgentResponse query(@Valid @RequestBody AgentQueryRequest request) { return service.query(request); }
    @PostMapping("/compare")
    public DocumentComparisonResponse compare(@Valid @RequestBody DocumentComparisonRequest request) { return service.compare(request); }
    @GetMapping("/sessions/{sessionId}")
    public AgentSessionResponse sessionHistory(@PathVariable UUID sessionId) { return service.sessionHistory(sessionId); }
    @GetMapping("/explain/{queryId}")
    public AgentTraceResponse explain(@PathVariable UUID queryId) { return service.explain(queryId); }
}
