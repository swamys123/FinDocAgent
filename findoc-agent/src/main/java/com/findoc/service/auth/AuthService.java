package com.findoc.service.auth;

import com.findoc.dto.request.AuthRequest;
import com.findoc.dto.response.AuthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {
    private static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEMO_USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final JwtService jwtService;
    private final long expirySeconds;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(JwtService jwtService, @Value("${jwt.expiry-seconds}") long expirySeconds) {
        this.jwtService = jwtService;
        this.expirySeconds = expirySeconds;
    }

    public AuthResponse authenticate(AuthRequest request) {
        if (!"demo@findoc.local".equals(request.username()) || !encoder.matches(request.password(), "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCi.sMKMTxGiRm3/zI/XtGi")) {
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
        }
        return new AuthResponse(jwtService.issue(DEMO_TENANT, DEMO_USER, request.username()), "Bearer", expirySeconds, DEMO_TENANT);
    }
}
