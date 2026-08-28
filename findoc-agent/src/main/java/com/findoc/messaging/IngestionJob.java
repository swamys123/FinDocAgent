package com.findoc.messaging;

import java.util.UUID;

public record IngestionJob(UUID documentId, UUID tenantId, UUID userId) {}