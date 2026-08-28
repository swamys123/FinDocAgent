package com.findoc.dto.response;

import java.util.UUID;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, UUID tenantId) {}
