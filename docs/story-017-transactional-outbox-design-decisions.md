# Story 017: Transactional Outbox Design Decisions

This document records the design decisions made for the Audit 1 transactional outbox implementation. The implementation handoff is [Story 017](./story-017-transactional-outbox.md).

## Context and scope

Document upload originally committed the document and source to PostgreSQL, then attempted to publish an ingestion job to Kafka from an after-commit callback. A process failure between the database commit and Kafka publication could leave a `PENDING` document that would never be ingested. A broker outage could also surface as an upload failure even though the document was already committed, and synchronous publication could keep the request thread waiting on Kafka.

The chosen scope was the first correctness-audit fix only: make upload-event creation durable and move Kafka work off the upload request. Keep the current ingestion topic and message contract. Broader consumer and dead-letter changes were left to their own audit steps.

## Decisions

### Persist the event in the upload transaction

The upload transaction now stores the document, its source bytes, and an outbox event together. If the transaction rolls back, none of these writes become visible; if it commits, the event is durably available for relay.

The existing `IngestionJob` record remains the payload contract. The outbox serializes it as JSON in PostgreSQL `JSONB`, and the relay deserializes that JSON back to the same record before publishing. This avoids changing the Kafka consumer or the topic's payload shape.

### Store the existing topic in configuration, not per row

The outbox does not store a topic column. Ingestion events in this path always use the configured existing ingestion topic, so persisting the same topic value on every row would duplicate configuration and could leave stale destinations after configuration changes. The relay continues to use `IngestionProducer`, which owns topic selection and publication behavior.

### Keep tenant attribution in the outbox

Each row has a required `tenant_id` foreign key to `tenants`. The serialized payload already contains the tenant and user IDs required by the existing ingestion contract; the separate tenant column records ownership/attribution at the durable event boundary and supports operational inspection.

### Use a pending marker and an indexed chronological queue

An event is pending while `sent_at` is `NULL`; successful publication records the timestamp. This small state representation is sufficient for the send-and-retry lifecycle without an extra status enum or a separate attempt state.

A partial index on `created_at` where `sent_at IS NULL` supports the relay's oldest-pending-first query without indexing completed rows. The UUID primary key is a deterministic tie-breaker for events created at the same timestamp.

### Claim work with row locks and `SKIP LOCKED`

The relay selects the oldest pending row with `FOR UPDATE SKIP LOCKED`. The row lock is held during publication and the sent-marker update, within one Spring-managed database transaction. Concurrent relay invocations or application instances skip a row currently being processed instead of publishing it concurrently.

The repository uses JDBC for this query and update because it expresses PostgreSQL's locking clause directly while participating in the same Spring datasource transaction as the upload and delivery services.

### Publish before marking sent

The relay calls the existing producer first and records `sent_at` only after publication succeeds. A send failure raises through the delivery transaction, so the sent-marker change does not commit and the row remains eligible for a later attempt.

Publication is performed by the scheduled relay, not the request thread. Poll interval and maximum batch size are configurable, with defaults of one second and twenty events per polling call. A failed event is logged and left pending; the next scheduled poll retries it rather than failing the already-committed upload.

### Accept at-least-once delivery

PostgreSQL and Kafka do not share one atomic commit here. If Kafka acknowledges a message and the application stops before the database transaction commits `sent_at`, that event can be published again. The selected failure bias is therefore at-least-once delivery: retain the event and risk a duplicate rather than mark it sent before Kafka has accepted it and risk losing ingestion.

Duplicate consumer delivery must be safe. That consumer-side guarantee is implemented separately by Audit 2; this outbox design does not claim exactly-once end-to-end delivery.

### Do not invent events for arbitrary pending documents

The orphan-recovery test was changed to model the actual crash window: it commits a pending document and its unsent outbox event, then verifies the relay publishes it and the consumer eventually makes the document `READY`.

No scheduled scan synthesizes outbox events for all old `PENDING` documents. Such a scan cannot distinguish a genuinely lost pre-outbox event from work already in flight or previously delivered, so it could introduce duplicate ingestion. Repairing records created before the outbox migration, if needed, should be an explicit and separately scoped backfill.

## Validation decisions

- Preserve the existing broker-unavailable assertion: upload returns successfully while the event remains pending.
- Preserve the request-latency assertion: a slow producer does not block upload.
- Update the orphan fixture—not its desired outcome—to represent a committed unsent event, then assert eventual `READY` status and `sent_at`.
- Keep these integration tests in the `correctness`-tagged Testcontainers suite, which exercises the PostgreSQL and Kafka boundary rather than replacing it with mocks.
