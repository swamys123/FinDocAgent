package com.findoc.dto.response;

import java.time.Instant;

public record SessionMessageResponse(String role, String content, Instant createdAt) {
}