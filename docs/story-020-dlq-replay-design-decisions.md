# Story 020: DLQ replay design decisions

This record documents the design decisions for authorized dead-letter replay in [Story 020](./story-020-dlq-replay.md). The replay behavior addresses the missing replay path and duplicate-work risks described in [Audit 3](./audit/correctness-audit.md).

## Goals

- Let a tenant administrator retry a failed ingestion job without re-uploading the document.
- Preserve the document's tenant and original owner identity when rebuilding the ingestion job.
- Make concurrent and repeated requests produce at most one new replay event for the failed-to-pending transition.
- Preserve the existing at-least-once Kafka and transactional-outbox model.
- Prevent callers from replaying another tenant's document or changing the job's tenant/user identifiers.

## Decisions

### 1. Replay by document UUID rather than adding a DLQ message ID

The current DLQ record key and `IngestionJob.documentId` identify the affected document; the application has no separate persisted DLQ-message identifier or DLQ registry. The endpoint therefore uses the document UUID:

```text
POST /api/v1/admin/dlq/{documentId}/replay
```

Replay does not accept a Kafka payload, tenant ID, or user ID from the caller. The document row is the source of truth for the tenant and owner, and the job is reconstructed from those values. A future requirement to select among multiple historical failures for one document would need a durable attempt/message record and a different identifier contract.

### 2. Require the existing tenant `ADMIN` role

The `users.role` column is the existing role source. Authentication includes this persisted role in the signed JWT, and the JWT filter maps it to a Spring Security `ROLE_<role>` authority. The `/api/v1/admin/**` route family requires `ROLE_ADMIN`; other authenticated APIs retain their prior authentication requirement.

This is tenant-admin authorization, not a platform-wide cross-tenant administrator. The tenant comes from the authenticated token and is always included in the document lookup and state update. Callers cannot elevate privileges through request fields.

Role changes affect newly issued tokens; existing signed tokens retain their role claim until expiry. The current system does not provide token revocation or immediate role-change invalidation. Admin provisioning remains a trusted operational responsibility through the existing role field; self-service role management is out of scope.

### 3. Atomically transition only a failed document and enqueue via the outbox

Replay uses a conditional database update scoped by document UUID, authenticated tenant ID, non-deleted state, and current `FAILED` status. Only a successful `FAILED` → `PENDING` update creates an `IngestionJob` and enqueues it through the existing outbox. The status transition and outbox insert run in the same database transaction:

- If the outbox insert or transaction fails, the state transition rolls back and the request can be retried.
- Concurrent requests cannot both claim the same `FAILED` state; the conditional update lets the database arbitrate the race.
- The existing relay publishes the event, preserving the project's durable at-least-once delivery behavior.

The service does not publish directly to Kafka or edit a DLQ record in place. This avoids a database/Kafka dual-write in the request path and uses the already established recovery mechanism.

### 4. Define replay behavior by current document status

| Current status | Result | Reason |
| --- | --- | --- |
| `FAILED` | Transition to `PENDING`, clear `error_msg`, retain `retry_count`, enqueue one event, return 202 | Starts a new attempt without losing cumulative attempt history. |
| `PENDING` | Do not enqueue another event; return 202 | Makes a retry after a lost HTTP response harmless while the queued work is pending. |
| `PROCESSING` | Return 409 | A worker already owns an active attempt; starting another could race with it. |
| `READY` | Return 409 | Successful work does not need replay; the consumer independently treats duplicate READY deliveries as no-ops. |
| Missing, deleted, or another tenant's document | Return 404 | Tenant-scoped lookup avoids exposing cross-tenant document existence. |

The status update and event enqueue are the idempotency boundary. If the first call's event is relayed and ingestion reaches `PROCESSING` before a duplicate request checks status, that duplicate may receive 409 rather than 202, but it cannot enqueue another event. The safety guarantee is no duplicate replay side effect, not an identical response under every race timing.

### 5. Preserve status lifecycle and cumulative retry count

The replay update clears the prior failure message and advances the document to `PENDING`. The existing ingestion claim then moves it to `PROCESSING`, and normal completion/failure logic determines `READY` or `FAILED`. The stored retry count is not reset by a manual replay; subsequent failed deliveries continue to increment it using the existing attempt-tracking behavior.

This keeps the status lifecycle centralized in the existing ingestion state machine and prevents a manual retry from erasing operational history.

### 6. Keep the API response minimal

The route returns HTTP 202 with no response body for both newly queued work and a pending no-op. Operators and clients can use the existing tenant-scoped document status endpoint to observe processing. Status conflicts use HTTP 409, and missing/cross-tenant documents use HTTP 404.

No new replay DTO, response persistence, or `Idempotency-Key` record is added. The conditional status transition and outbox transaction already provide the necessary duplicate-side-effect protection for this operation.

### 7. Limit JWT filter error conversion to token parsing

The JWT filter catches malformed/invalid token errors while parsing claims and returns 401. It does not catch exceptions from downstream controllers. This preserves endpoint responses such as the replay service's 409 instead of misreporting controller failures as invalid authentication.

## Deliberate non-goals

- Platform-wide admin access or cross-tenant replay.
- A separate DLQ message/attempt table, message ID, or replay of arbitrary raw Kafka payloads.
- Direct synchronous publication from the endpoint or exactly-once delivery across PostgreSQL and Kafka.
- Resetting cumulative `retry_count`, changing retry/backoff configuration, or introducing a replay limit.
- Returning the Kafka offset, delivery result, or a replay-specific response body.
- Role-management APIs, self-service elevation, and immediate revocation of already-issued JWT role claims.
- Fixing other Audit 3 findings such as DLQ publish failure recovery, topic provisioning, DLQ metrics, or failure-reason visibility.

## Validation decisions

- Unit tests cover the failed transition and job identity, repeated pending calls, conflict statuses, and missing/cross-tenant lookup behavior.
- Repository tests exercise the real conditional SQL transition, tenant predicate, repeat-call behavior, error clearing, and retry-count retention.
- Web tests cover admin authorization from signed role claims, denial for a USER, tenant identity extraction from the JWT, and preservation of a controller 409 response.
- The correctness integration test verifies that the outbox job preserves document, tenant, and owner IDs and that normal Kafka ingestion reaches `READY`.
- The complete Java unit suite passes. The focused correctness integration test compiles but requires Docker-enabled Testcontainers to execute.
