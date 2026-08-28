package com.findoc.service.auth;

import com.findoc.dto.request.AuthRequest;
import com.findoc.dto.response.AuthResponse;
import com.findoc.entity.User;
import com.findoc.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final long expirySeconds;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(JwtService jwtService, UserRepository userRepository, @Value("${jwt.expiry-seconds}") long expirySeconds) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.expirySeconds = expirySeconds;
    }

    public AuthResponse authenticate(AuthRequest request) {
        User user = userRepository.findByUsernameAndDeletedAtIsNull(request.username())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!encoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        return new AuthResponse(
            jwtService.issue(user.getTenant().getId(), user.getId(), user.getUsername()),
            "Bearer",
            expirySeconds,
            user.getTenant().getId()
        );
    }
}
