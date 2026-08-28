package com.findoc.dto.response;

import java.util.List;
import java.util.UUID;

public record AgentResponse(UUID queryId, UUID sessionId, String answer, String intent, List<String> sources, List<String> stepsTaken, double confidence) {}
