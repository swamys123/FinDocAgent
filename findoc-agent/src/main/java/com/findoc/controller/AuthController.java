package com.findoc.controller;

import com.findoc.dto.request.AuthRequest;
import com.findoc.dto.response.AuthResponse;
import com.findoc.service.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }
    @PostMapping("/token")
    public AuthResponse token(@Valid @RequestBody AuthRequest request) { return authService.authenticate(request); }
}
