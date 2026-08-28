package com.findoc.controller;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.response.AgentResponse;
import com.findoc.service.agent.AgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service) { this.service = service; }
    @PostMapping("/query")
    public AgentResponse query(@Valid @RequestBody AgentQueryRequest request) { return service.query(request); }
}
