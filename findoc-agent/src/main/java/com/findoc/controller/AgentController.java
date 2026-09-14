package com.findoc.controller;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.request.DocumentComparisonRequest;
import com.findoc.dto.response.AgentSessionResponse;
import com.findoc.dto.response.AgentResponse;
import com.findoc.dto.response.AgentTraceResponse;
import com.findoc.dto.response.DocumentComparisonResponse;
import com.findoc.service.agent.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
@Tag(name = "Agent")
@SecurityRequirement(name = "bearer-jwt")
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service) { this.service = service; }
    @PostMapping("/query")
    @Operation(summary = "Query documents with the agent", responses = {
        @ApiResponse(responseCode = "200", description = "Answer generated", content = @Content(schema = @Schema(implementation = AgentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public AgentResponse query(@Valid @RequestBody AgentQueryRequest request) { return service.query(request); }
    @PostMapping("/compare")
    @Operation(summary = "Compare two documents", responses = {
        @ApiResponse(responseCode = "200", description = "Comparison generated", content = @Content(schema = @Schema(implementation = DocumentComparisonResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public DocumentComparisonResponse compare(@Valid @RequestBody DocumentComparisonRequest request) { return service.compare(request); }
    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "Get agent session history", responses = {
        @ApiResponse(responseCode = "200", description = "Session history returned", content = @Content(schema = @Schema(implementation = AgentSessionResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "404", description = "Session not found")
    })
    public AgentSessionResponse sessionHistory(@Parameter(description = "Session identifier", required = true) @PathVariable UUID sessionId) { return service.sessionHistory(sessionId); }
    @GetMapping("/explain/{queryId}")
    @Operation(summary = "Explain an agent query", responses = {
        @ApiResponse(responseCode = "200", description = "Trace returned", content = @Content(schema = @Schema(implementation = AgentTraceResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "404", description = "Query trace not found")
    })
    public AgentTraceResponse explain(@Parameter(description = "Query identifier", required = true) @PathVariable UUID queryId) { return service.explain(queryId); }
}
