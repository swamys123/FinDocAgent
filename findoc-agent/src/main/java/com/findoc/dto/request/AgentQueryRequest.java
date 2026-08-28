package com.findoc.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public record AgentQueryRequest(@NotBlank String query, List<UUID> documentIds, UUID sessionId) {}
