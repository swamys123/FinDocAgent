package com.findoc.dto.response;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(UUID documentId, String filename, String fileType, String status, int chunkCount, Instant createdAt) {}
