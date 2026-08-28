package com.findoc.messaging;

import java.util.UUID;

public record IngestionJob(UUID documentId, UUID tenantId, UUID userId, int attemptNumber) {
	public IngestionJob(UUID documentId, UUID tenantId, UUID userId) {
		this(documentId, tenantId, userId, 1);
	}

	public IngestionJob {
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("Attempt number must be positive");
		}
	}
}