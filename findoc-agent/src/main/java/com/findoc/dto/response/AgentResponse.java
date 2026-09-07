package com.findoc.dto.response;

import java.util.List;
import java.util.UUID;

public record AgentResponse(
	UUID queryId,
	UUID sessionId,
	String answer,
	String intent,
	List<AgentSourceResponse> sources,
	List<String> stepsTaken,
	double confidence
) {
}
