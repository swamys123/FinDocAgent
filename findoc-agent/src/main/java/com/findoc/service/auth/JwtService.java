package com.findoc.service.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiry-seconds}") long expirySeconds) {
        if (secret.length() < 32) throw new IllegalArgumentException("JWT secret must be at least 32 characters");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
    }

    public String issue(UUID tenantId, UUID userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder().subject(username).claim("tenant_id", tenantId.toString())
            .claim("user_id", userId.toString()).issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(expirySeconds))).signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
