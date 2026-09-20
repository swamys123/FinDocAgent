package com.findoc.dto.response;

import java.time.Instant;
import java.util.UUID;

public record QueryTraceSummaryResponse(
    UUID queryId,
    String query,
    String intent,
    Integer durationMs,
    Instant createdAt
) {
}
