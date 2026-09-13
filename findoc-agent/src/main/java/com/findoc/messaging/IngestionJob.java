package com.findoc.messaging;

import java.util.UUID;

public record IngestionJob(UUID documentId, UUID tenantId, UUID userId) {
	public IngestionJob(UUID documentId, UUID tenantId, UUID userId) {
		this.documentId = documentId;
		this.tenantId = tenantId;
		this.userId = userId;
	}
}