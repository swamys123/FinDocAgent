# Story 019: Audit 4 tenant-scoped idempotency keys

## Completed work

- Added Liquibase-managed `idempotency_keys` storage with a tenant/key primary key, request fingerprint, stored response status/body, and 24-hour expiry.
- Added transactionally atomic request execution for `POST /api/v1/documents/upload`, `POST /api/v1/agent/query`, and `POST /api/v1/agent/compare`.
- Matching fingerprints replay the original status and JSON body. A different fingerprint for the same tenant/key returns 422. The unique constraint arbitrates concurrent requests.
- Fingerprints include upload metadata and bytes, or the endpoint and normalized JSON request fields. Requests without an idempotency key retain existing behavior.
- Added an `Idempotent-Replayed` response header and allowed the idempotency request header through CORS.
- Added focused controller, fingerprint, and Testcontainers coverage.

## Pending work

- Audit 4 content-hash deduplication, the unique live-document content index, and related embedding/retrieval guarantees.
- Frontend idempotency-key generation and retry UX.
- Audit 3 DLQ safety, observability, and replayability.

## Validation performed

- `AgentControllerTest`, `DocumentControllerTest`, and `RequestFingerprintTest` pass.
- Focused `UploadIdempotencyIntegrationTest` Testcontainers cases pass for same-key replay, changed-body rejection, tenant isolation, concurrent requests, expiry, and rollback when the operation fails.

## Next implementation item

Implement Audit 3 DLQ safety, observability, and replayability, then complete the remaining Audit 4 content-hash deduplication work.
