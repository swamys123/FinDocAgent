package com.findoc.controller;

import com.findoc.service.document.DlqReplayService;
import com.findoc.util.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/dlq")
@Tag(name = "Administration")
@SecurityRequirement(name = "bearer-jwt")
public class AdminDlqReplayController {
    private final DlqReplayService replayService;

    public AdminDlqReplayController(DlqReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/{documentId}/replay")
    @Operation(summary = "Replay a failed ingestion job", responses = {
        @ApiResponse(responseCode = "202", description = "Replay queued or already pending"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Tenant administrator role required"),
        @ApiResponse(responseCode = "404", description = "Document not found in the authenticated tenant"),
        @ApiResponse(responseCode = "409", description = "Document is processing or already ready")
    })
    public ResponseEntity<Void> replay(
        @Parameter(description = "Document UUID used as the DLQ message identifier", required = true)
        @PathVariable UUID documentId
    ) {
        replayService.replay(documentId, TenantContext.tenantId());
        return ResponseEntity.accepted().build();
    }
}
