# Story 020: Tenant-admin DLQ replay

Design rationale and trade-offs are documented in [Story 020 design decisions](./story-020-dlq-replay-design-decisions.md).

## Completed work

- Added the persisted user role to signed JWTs and mapped it to Spring Security authorities. The `/api/v1/admin/**` route family now requires `ROLE_ADMIN`.
- Added `POST /api/v1/admin/dlq/{documentId}/replay`, where the document UUID is the DLQ message identifier. The caller cannot supply tenant or owner IDs; the document lookup is scoped to the JWT tenant.
- Replay conditionally moves a non-deleted `FAILED` document to `PENDING`, clears its prior failure message, retains its cumulative retry count, and enqueues the original document/tenant/owner `IngestionJob` through the transactional outbox.
- Repeated requests while the document remains `PENDING` are accepted without another outbox event. `PROCESSING` and `READY` return conflict; a missing or cross-tenant document returns not found.
- Narrowed JWT filter error handling so exceptions from controllers retain their intended HTTP status instead of being reported as invalid-token responses.
- Added authentication, route authorization, replay idempotency, tenant lookup, and ingestion lifecycle coverage. Updated the Audit 3 findings and test inventory.
- Admin role assignment remains a trusted provisioning responsibility through the existing `users.role` field; there is no self-service role elevation endpoint.

## Pending work

- Run `DeadLetterContractIntegrationTest` against Docker-enabled Testcontainers to verify the replay event reaches Kafka and the document completes as `READY`.
- Complete the remaining Audit 3 DLQ publish atomicity/recovery, topic provisioning, observability, and failure-reason visibility work.

## Validation performed

- The complete Java unit-test suite passed, including `AuthServiceTest`, `DlqReplayServiceTest`, `AdminDlqReplayControllerTest`, and `DocumentRepositoryTest`.
- Java main and test sources compiled successfully.
- Attempted the focused `DeadLetterContractIntegrationTest`; Testcontainers could not initialize because Docker is unavailable in the current environment.
- `git diff --check` passed.

## Next implementation item

Run the focused replay correctness integration test in a Docker-enabled environment, then continue with Audit 3 DLQ delivery safety and observability.
