package com.findoc.dto.response;

import java.util.UUID;

public record AgentSourceResponse(
    UUID chunkId,
    UUID documentId,
    String filename,
    String content,
    double similarityScore,
    Integer pageNumber
) {
}