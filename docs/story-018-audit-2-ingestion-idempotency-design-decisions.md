# Story 018: Audit 2 Ingestion Idempotency Design Decisions

This document records the design decisions for the Audit 2 ingestion-idempotency implementation. The implementation handoff is [Story 018](./story-018-audit-2-ingestion-idempotency.md).

## Context and scope

Kafka ingestion is at-least-once: a delivery can be repeated after a consumer restart, rebalance, or failed attempt. The previous ingestion flow could create duplicate live chunks under concurrent delivery, retain an active database transaction during embedding-provider calls, repeat successful paid embedding calls on retry, and leave a document in `PROCESSING` indefinitely after a crash.

The scope was to make document ingestion safe to retry and recover without changing the `IngestionJob` message contract or introducing exactly-once claims across PostgreSQL and Kafka. `READY` is the only duplicate-delivery no-op. A fresh `PROCESSING` document is still treated as an active claim and is not silently accepted as another successful delivery.

## Decisions

### Enforce uniqueness for live chunks in PostgreSQL

A partial unique index enforces one non-deleted chunk per `(document_id, chunk_index)`. Soft-deleted history is excluded so a previously removed chunk index can be reused. This database constraint is the final protection against duplicate writes, independent of application-level claim behavior.

The Liquibase migration checks for existing live duplicates and halts rather than deleting or merging data automatically. Any such data needs explicit remediation before the constraint can be installed.

### Use conflict-upserting writes and retain stable chunk identity

Chunk persistence uses PostgreSQL `INSERT … ON CONFLICT` against the partial index. Existing live rows are updated in place, preserving their IDs across re-ingestion. After all chunks have been prepared and written successfully, live trailing chunks whose indexes no longer exist are soft-deleted.

This avoids delete-and-reinsert churn while still ensuring that a shorter replacement source does not leave stale chunks available to retrieval. The upsert and trailing cleanup run in the final completion transaction, so a failure cannot expose a partially refreshed live chunk set.

### Make status changes conditional and tenant-scoped

Claim, completion, and failure transitions use conditional SQL updates that include document and tenant identity and the expected current status. The database update, rather than a previously loaded entity state, arbitrates concurrent claims and completion races.

An already-`READY` document is a no-op so a normal duplicate delivery can be acknowledged. A fresh `PROCESSING` document remains an error: treating it as successful could acknowledge work while a different attempt is still responsible for completing it. Only the stale-claim recovery path can release an abandoned `PROCESSING` status.

Completion and chunk writes also require the document to remain non-deleted and in the expected processing state. If deletion wins a race, the final transaction fails and rolls back instead of restoring stale document state or committing live chunks.

### Keep provider calls outside database transactions

Text extraction and embedding orchestration run outside a long-lived database transaction. Holding a database transaction open across an external provider request would tie database resources to provider latency and could keep locks or connections occupied during a slow or failed request.

Short transactions are used for claim changes, embedding progress, and final completion. The final transaction writes the prepared live chunks, cleans up obsolete trailing chunks and staged data, and transitions the document to `READY` atomically.

### Stage successful embeddings for retry reuse

Each successfully computed embedding is durably stored in an ingestion staging table, keyed by tenant, document, and chunk index, together with its source content and token count. On a retry, a staged vector is reused only when the chunk content still matches; changed content is re-embedded.

This trades temporary storage and migration complexity for avoiding repeated provider calls after a later chunk fails or a worker crashes. Staging rows are removed as part of successful finalization. They are not visible to retrieval, which continues to read live document chunks only.

### Recover abandoned claims with a bounded lease and the outbox

Progress writes update the document heartbeat. A scheduled recovery scan considers only non-deleted `PROCESSING` documents older than the configured lease and processes a bounded batch. The recovery transaction conditionally resets a still-stale claim to `PENDING` and writes its replay event to the existing transactional outbox together.

The outbox is used rather than direct Kafka publication so the reset cannot commit without a durable replay event, and a failed publication remains recoverable by the existing relay. The default lease is five minutes, polling interval thirty seconds, and batch size twenty; all are configurable. These defaults require operational review against real extraction/provider timeouts and document sizes. A lease that is too short risks overlapping attempts; one that is too long delays recovery.

### Count failed delivery attempts once

Each failed ingestion delivery updates the retry count through the conditional failure transition. The final dead-letter recovery does not increment it a second time. A stale attempt is counted when recovery resets and re-enqueues it, since the interrupted attempt did not complete.

This keeps the stored retry count aligned with work attempts and allows the DLQ path to report the configured delivery history without a separate counter update that could race with state transitions.

### Keep record acknowledgment explicit and route poison records aside

The Kafka listener uses record-level acknowledgment so successfully handled records are committed individually. `ErrorHandlingDeserializer` wraps the trusted job deserializer, allowing malformed payloads to reach the configured recovery/DLQ path rather than repeatedly blocking later valid records at the same partition offset.

This improves partition progress but does not provide exactly-once processing. Duplicate deliveries remain expected and are handled by the claim, upsert, and `READY` no-op behavior above.

## Validation decisions

- Keep database-backed correctness tests for the partial unique index, including rejection of a second live chunk and acceptance after soft deletion.
- Exercise repeated, concurrent, and retry-driven ingestion against Testcontainers PostgreSQL/pgvector and Kafka.
- Verify the provider is called outside a transaction and successful embeddings are not recomputed after a retry or stale-claim recovery.
- Verify that a duplicate `READY` delivery neither changes chunk state nor blocks a subsequent valid message.
- Verify tenant isolation, deletion during ingestion, retry counts, stale-claim outbox recovery, and poison-record progress.
- Keep focused unit tests for orchestration behavior and run the normal unit suite separately from the tagged `correctnessTest` integration suite.

## Known trade-offs and follow-up

- The live-chunk unique index is document-scoped; tenant authorization remains enforced by tenant-scoped document and repository operations.
- Staged embeddings consume database storage while a job is retrying. Successful completion cleans them up, while operational monitoring and cleanup policy for permanently stranded staging data remain a separate consideration.
- Lease-based recovery depends on the heartbeat and configured timeout assumptions. Production values should be tuned using actual maximum processing duration and provider timeouts.
- PostgreSQL and Kafka do not share one atomic transaction in this flow. The design favors durable replay and idempotent at-least-once handling over claiming end-to-end exactly-once delivery.
