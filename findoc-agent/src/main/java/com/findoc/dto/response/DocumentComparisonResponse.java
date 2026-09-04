package com.findoc.dto.response;

import java.util.List;
import java.util.UUID;

public record DocumentComparisonResponse(
    UUID queryId,
    List<String> similarities,
    List<String> differences,
    String summary,
    List<AgentSourceResponse> documentASources,
    List<AgentSourceResponse> documentBSources
) {
}