package com.findoc.dto.response;

import java.util.List;
import java.util.UUID;

public record AgentTraceResponse(
    UUID queryId,
    String query,
    String intent,
    List<String> fullTrace,
    Integer totalDurationMs
) {
}