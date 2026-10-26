# Story 019: Idempotency design decisions

This record documents the decisions made for tenant-scoped HTTP idempotency in [Story 019](./story-019-idempotency-keys.md). The feature addresses the retry and duplicate-work risks described in [Audit 4](./audit/correctness-audit.md).

## Goals

- Let clients safely retry selected writes after a timeout or lost response.
- Return the original status and JSON response for the same tenant, key, and request.
- Reject reuse of a key for a different request with HTTP 422.
- Resolve concurrent uses of the same tenant/key through a database uniqueness constraint.
- Preserve existing behavior for callers that do not send an idempotency key.

## Decisions

### 1. Protect only writes whose retry can repeat meaningful work

Idempotency keys are supported on:

| Endpoint | Reason |
| --- | --- |
| `POST /api/v1/documents/upload` | Creates a document and source blob, and queues ingestion through the outbox. Retrying can otherwise create duplicate documents and ingestion work. |
| `POST /api/v1/agent/query` | Generates an answer and persists session messages and a query trace. Retrying can repeat provider work and create duplicate history. |
| `POST /api/v1/agent/compare` | Performs comparison work and persists a session, messages, and a trace. Retrying can repeat provider work and create duplicate history. |

`DELETE /api/v1/documents/{id}` is excluded: it is not a POST retry workflow, and deletion already represents a single state transition (a repeated request currently returns not found). `POST /api/v1/auth/token` is excluded because it issues a bearer token without the durable business mutation this feature targets; persisting and replaying credentials/tokens is not part of the contract.

### 2. Make the header optional

`Idempotency-Key` is optional to keep existing clients working without changes. When absent, controllers invoke the existing service path without creating a key record. When present, the key must be nonblank and no longer than 255 characters; invalid values use the existing HTTP 400 validation path.

### 3. Scope a key to the tenant, not globally or to one endpoint

The database primary key is `(tenant_id, idempotency_key)`. This isolates tenants while making a client's key unique across the tenant's supported operations. The request fingerprint includes the HTTP method and route, so reusing one key on a different endpoint is treated as a different request and returns 422.

The key is not scoped to `user_id`. This follows the tenant-scoped contract chosen for this implementation; clients should generate unique keys per logical operation and avoid sharing keys across users. If per-user replay authorization is required, user identity should be added to the key scope and tested explicitly.

### 4. Compare requests using SHA-256 fingerprints

Fingerprints are SHA-256 digests, stored as 64-character hexadecimal strings. Length-prefixing each fingerprint component avoids ambiguity when the input fields are concatenated.

- Upload: endpoint identifier, original filename, content type, and complete file bytes.
- Agent query and comparison: endpoint identifier and Jackson serialization of the validated request record, including all request fields.

Including upload metadata makes a changed filename or media type a different request even when the bytes match. JSON requests are compared by their parsed request values rather than raw HTTP formatting; whitespace and JSON property order do not create a mismatch. Endpoint identity prevents a request payload shared across routes from being treated as equivalent.

### 5. Persist the original status and JSON response body

The idempotency record stores the successful response status and serialized response body. On a replay, the body is deserialized into the endpoint's response type, and the stored status/body are returned. This preserves the response contract and generated identifiers without rerunning service logic.

Response JSON is stored as PostgreSQL `JSONB`; replay preserves JSON structure and values, not byte-for-byte formatting or arbitrary response headers. The `Idempotent-Replayed` response header is `false` for the first keyed request and `true` for a replay. No replay header is added when the key is omitted. CORS permits clients to send `Idempotency-Key` and exposes `Idempotent-Replayed`.

### 6. Commit reservation, business effects, and response atomically

The idempotency executor runs in a database transaction. It inserts a reservation, runs the endpoint operation, and persists the resulting response before commit. The reservation and business effects therefore commit together. If the operation or response persistence fails, the transaction rolls back, leaving no key reservation that could block a retry.

The response fields are nullable only while the transaction is in progress; a database check constraint requires the status and body to be either both null or both present. Since the reservation is not committed independently, another transaction must not observe a durable in-progress row.

### 7. Let PostgreSQL arbitrate concurrent requests

The unique `(tenant_id, idempotency_key)` primary key is the concurrency authority. `INSERT ... ON CONFLICT DO NOTHING` avoids a read-before-write race. A competing transaction waits for the conflicting insert to resolve; afterward it reads the committed fingerprint and response:

- Same fingerprint: replay the original status/body.
- Different fingerprint: return 422.

No application-level lock or distributed lock is introduced.

### 8. Expire keys after 24 hours

Successful keys remain valid for 24 hours, matching the audit recommendation and the approved behavior for this implementation. An expired row is deleted when that same key is used again, allowing a new operation; a scheduled cleanup also removes expired rows periodically. An index on `expires_at` supports the cleanup query.

After expiry, reuse of a key is a new operation, not a replay. Clients should therefore retry within the retention window.

### 9. Use a focused Liquibase migration and JDBC persistence

The table is introduced through a new Liquibase changeset rather than modifying an existing migration. It has a tenant foreign key, a composite primary key, a fixed-length fingerprint, JSONB response storage, timestamps, and an expiry index. The executor uses parameterized `JdbcTemplate` SQL for the reservation, response update, replay lookup, and cleanup.

## Deliberate non-goals

- Content-based upload deduplication, document content hashes, or a unique live-document index. Those are separate Audit 4 concerns and remain pending.
- Frontend key generation and retry UX. The server accepts the header, but the frontend does not yet generate or persist operation keys.
- Idempotency for document deletion, token issuance, or other read/write APIs not listed above.
- Caching failures or provider exceptions. Failed operations roll back the reservation so clients can retry.
- Byte-identical HTTP response replay, arbitrary header replay, or replay beyond the 24-hour retention window.

## Validation decisions

Coverage is split by concern:

- Controller tests verify keyed replay response behavior and that a replay does not rerun the service.
- Fingerprint unit tests verify stability and sensitivity to endpoint, upload metadata, bytes, and JSON fields.
- Testcontainers correctness tests verify PostgreSQL migration/storage behavior, tenant isolation, same-key replay, mismatch rejection, concurrent arbitration, expiry, response deserialization, and transaction rollback.

Run the focused correctness class from `findoc-agent/`:

```bash
./gradlew correctnessTest --tests com.findoc.correctness.UploadIdempotencyIntegrationTest --console=plain
```
