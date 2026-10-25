# Story 017: Transactional ingestion outbox

## Completed work

- Added a Liquibase-managed `outbox` table with JSONB payloads, tenant attribution, creation/sent timestamps, and a partial index for pending events.
- Document upload now writes the existing `IngestionJob` payload to the outbox in the same database transaction as the document and source.
- Added a scheduled relay that locks pending rows with `SELECT ... FOR UPDATE SKIP LOCKED`, publishes through the existing ingestion producer/topic, and marks each row sent in the same database transaction.
- Broker errors leave the outbox row unsent for a later relay attempt and no longer fail or block the upload request.
- Corrected the Audit 1 orphan scenario to commit an unsent outbox event, and asserted successful relay, eventual ingestion, and sent marking. The broker-failure and request-latency assertions remain intact.

## Pending work

- Kafka publication remains at-least-once if the process stops after Kafka acknowledges the message but before the sent marker commits. Audit 2 now makes duplicate ingestion deliveries idempotent.
- Continue with Audit 3 dead-letter safety, observability, and replayability.

## Validation performed

- `./gradlew test --tests com.findoc.service.document.DocumentServiceTest --console=plain` — passed.
- `JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew correctnessTest --tests com.findoc.correctness.UploadPublishAtomicityIntegrationTest --console=plain` — all three Audit 1 integration tests passed using Testcontainers.

## Next implementation item

Implement Audit 3 dead-letter safety, observability, and replayability.
