package com.findoc.messaging;

import java.util.UUID;

public record IngestionMessage(UUID documentId, UUID tenantId, UUID userId, String filePath,
                               String fileType, int attemptNumber) {
}