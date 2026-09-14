package com.findoc.controller;

import com.findoc.dto.request.AuthRequest;
import com.findoc.dto.response.AuthResponse;
import com.findoc.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {
    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }
    @PostMapping("/token")
    @Operation(summary = "Issue an access token", description = "Authenticates a user within a tenant and returns a JWT containing tenant and user claims.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Authenticated", content = @Content(schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
        })
    public AuthResponse token(@Valid @RequestBody AuthRequest request) { return authService.authenticate(request); }
}
