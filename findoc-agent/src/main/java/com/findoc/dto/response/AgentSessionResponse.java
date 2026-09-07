package com.findoc.dto.response;

import java.util.List;
import java.util.UUID;

public record AgentSessionResponse(UUID sessionId, List<SessionMessageResponse> messages) {
}