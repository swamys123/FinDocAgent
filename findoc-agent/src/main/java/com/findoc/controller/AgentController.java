package com.findoc.controller;

import com.findoc.dto.request.AgentQueryRequest;
import com.findoc.dto.request.DocumentComparisonRequest;
import com.findoc.dto.response.AgentSessionResponse;
import com.findoc.dto.response.AgentResponse;
import com.findoc.dto.response.AgentTraceResponse;
import com.findoc.dto.response.DocumentComparisonResponse;
import com.findoc.dto.response.QueryTraceSummaryResponse;
import com.findoc.service.agent.AgentService;
import com.findoc.service.idempotency.IdempotencyExecutor;
import com.findoc.service.idempotency.RequestFingerprint;
import com.findoc.util.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
@Tag(name = "Agent")
@SecurityRequirement(name = "bearer-jwt")
public class AgentController {
    private static final String QUERY_ENDPOINT = "POST /api/v1/agent/query";
    private static final String COMPARE_ENDPOINT = "POST /api/v1/agent/compare";

    private final AgentService service;
    private final IdempotencyExecutor idempotencyExecutor;
    private final RequestFingerprint requestFingerprint;

    public AgentController(AgentService service,
                           IdempotencyExecutor idempotencyExecutor,
                           RequestFingerprint requestFingerprint) {
        this.service = service;
        this.idempotencyExecutor = idempotencyExecutor;
        this.requestFingerprint = requestFingerprint;
    }

    @PostMapping("/query")
    @Operation(summary = "Query documents with the agent", responses = {
        @ApiResponse(responseCode = "200", description = "Answer generated", content = @Content(schema = @Schema(implementation = AgentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "422", description = "Idempotency-Key was reused with a different request"),
        @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public ResponseEntity<AgentResponse> query(
        @Valid @RequestBody AgentQueryRequest request,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(service.query(request));
        }
        var result = idempotencyExecutor.execute(
            TenantContext.tenantId(),
            idempotencyKey,
            requestFingerprint.json(QUERY_ENDPOINT, request),
            HttpStatus.OK,
            AgentResponse.class,
            () -> service.query(request));
        return ResponseEntity.status(result.status())
            .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
            .body(result.body());
    }
    @PostMapping("/compare")
    @Operation(summary = "Compare two documents", responses = {
        @ApiResponse(responseCode = "200", description = "Comparison generated", content = @Content(schema = @Schema(implementation = DocumentComparisonResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "422", description = "Idempotency-Key was reused with a different request"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<DocumentComparisonResponse> compare(
        @Valid @RequestBody DocumentComparisonRequest request,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(service.compare(request));
        }
        var result = idempotencyExecutor.execute(
            TenantContext.tenantId(),
            idempotencyKey,
            requestFingerprint.json(COMPARE_ENDPOINT, request),
            HttpStatus.OK,
            DocumentComparisonResponse.class,
            () -> service.compare(request));
        return ResponseEntity.status(result.status())
            .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
            .body(result.body());
    }
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
    @GetMapping("/traces/recent")
    @Operation(summary = "List the caller's most recent query traces", responses = {
        @ApiResponse(responseCode = "200", description = "Recent traces returned", content = @Content(array = @ArraySchema(schema = @Schema(implementation = QueryTraceSummaryResponse.class)))),
        @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public List<QueryTraceSummaryResponse> recentTraces() { return service.recentTraces(); }
}
