# Story 018: Audit 2 ingestion idempotency

## Completed work

- Added a Liquibase partial unique index on live `document_chunks (document_id, chunk_index)` rows, with a precondition that stops safely if existing live duplicates are present.
- Replaced live chunk inserts with tenant-scoped PostgreSQL upserts and soft-delete cleanup for obsolete trailing chunks.
- Added tenant-scoped conditional status updates for ingestion claims, completion, and failure handling. `READY` alone is a duplicate-delivery no-op; a fresh `PROCESSING` claim remains an error.
- Moved embedding-provider calls outside database transactions. Persist each successful embedding in a staging table and reuse it on Kafka retries; write all live chunks and set `READY` in one final transaction.
- Added processing heartbeats and a bounded lease recovery scheduler. Stale `PROCESSING` documents are reset to `PENDING` and re-enqueued through the transactional outbox in one transaction.
- Explicitly configured record-level Kafka acknowledgments and `ErrorHandlingDeserializer`; malformed messages are routed to the DLQ instead of blocking valid messages behind them.
- Retry counts now record failed delivery attempts without incrementing again during final DLQ recovery.
- Updated the Audit 2 audit and implementation status and added coverage for unique-index behavior, upsert/re-ingestion, tenant isolation, duplicate READY delivery, concurrency, retries, poison messages, deletion during ingestion, and crash recovery that reuses staged embeddings.

## Pending work

- Audits 3 and 4 remain pending, except Audit 3 finding b1 (accurate retry count), which is resolved by the atomic failure transition.
- Confirm lease and recovery polling settings against production document sizes and provider timeout behavior.

## Validation performed

- `./gradlew test --tests com.findoc.service.document.IngestionServiceTest --console=plain` — passed.
- `JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew correctnessTest --tests com.findoc.correctness.IngestionIdempotencyIntegrationTest --console=plain` — passed against Testcontainers PostgreSQL/pgvector and Kafka.
- Focused correctness tests for `PoisonMessageIntegrationTest` and `DeadLetterContractIntegrationTest.failedDocumentRecordsTheRealNumberOfDeliveryAttempts` — passed.

## Next implementation item

Begin Audit 3 DLQ safety, observability, and replayability.
