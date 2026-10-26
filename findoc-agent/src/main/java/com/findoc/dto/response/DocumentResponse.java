package com.findoc.dto.response;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(UUID documentId, String filename, String fileType, String status, int chunkCount,
                               Instant createdAt, boolean duplicate, String errorMessage) {
    public DocumentResponse(UUID documentId, String filename, String fileType, String status,
                            int chunkCount, Instant createdAt, boolean duplicate) {
        this(documentId, filename, fileType, status, chunkCount, createdAt, duplicate, null);
    }

    public DocumentResponse(UUID documentId, String filename, String fileType, String status,
                            int chunkCount, Instant createdAt) {
        this(documentId, filename, fileType, status, chunkCount, createdAt, false, null);
    }
}
