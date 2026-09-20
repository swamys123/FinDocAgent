package com.findoc.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentTraceStepResponse(
    int step,
    String tool,
    JsonNode input,
    JsonNode output,
    Integer durationMs
) {
}