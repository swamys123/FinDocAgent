# Distributed-systems correctness audit

Scope (Audits 1–4): read-only audit of the upload → Kafka → ingestion pipeline (Spring Boot 3.2.12, spring-kafka 3.1.10,
kafka-clients 3.6.2). No production code was changed. Every UNSAFE finding below is backed by a failing test in
`findoc-agent/src/test/java/com/findoc/correctness/` (see [Tests](#tests-and-the-finding-each-proves)).

Paths are relative to `findoc-agent/src/main/` unless stated. Verdicts: SAFE / UNSAFE / UNKNOWN.

## Audit 1 – Is "persist document" + "publish message" atomic?

**No.** Row and BYTEA content commit in one transaction; the Kafka send is a separate, later, non-durable step.

| Fact | Evidence |
|---|---|
| Upload endpoint → `service.upload(file)`, returns 202 | `java/com/findoc/controller/DocumentController.java:30,36` |
| `upload` is `@Transactional`; saves `Document` (status `PENDING`) and `DocumentSource` (BYTEA) in one tx | `java/com/findoc/service/document/DocumentService.java:50-69`; BYTEA `resources/db/changelog/db.changelog-003-ingestion.xml:18` |
| Send is registered as a manual `TransactionSynchronization.afterCommit` (no `@TransactionalEventListener`) | `DocumentService.java:66, 123-134` |
| `kafkaTemplate.send(...).get()` — blocks, no timeout; failure → `IllegalStateException` after the commit | `java/com/findoc/messaging/KafkaIngestionProducer.java:23-29` |
| Only `IllegalArgumentException`/`NoSuchElementException`/`BadCredentialsException` are mapped; the `IllegalStateException` becomes a 500 | `java/com/findoc/exception/GlobalExceptionHandler.java:10-15` |
| Producer settings: only bootstrap + serializers configured; the rest are kafka-clients 3.6.2 defaults: `acks=all`, `enable.idempotence=true`, `retries=2147483647`, `delivery.timeout.ms=120000`, `max.in.flight=5`, `max.block.ms=60000` | `resources/application.yml:20-24`; defaults read from `ProducerConfig.configDef()` of the 3.6.2 jar |
| No scheduler / startup recovery / sweeper for `PENDING` documents | grep of `src/main` for `@Scheduled`, `EnableScheduling`, `ApplicationReadyEvent`, `CommandLineRunner`, `ApplicationRunner`, `@PostConstruct`, `SmartLifecycle` – none relevant |
| Consumer cannot see the message before commit (send is in `afterCommit`) | `DocumentService.java:130-131` |

| Scenario | Outcome | Evidence | Severity | Verdict |
|---|---|---|---|---|
| 1. Send inside tx, then rollback | OK – send is after commit, never fires on rollback | `DocumentService.java:123-134` | n/a | SAFE |
| 2. Commit, crash before send | **Stuck document** (`PENDING`, no message, nothing recovers it) | `DocumentService.java:66,130-131`; no sweeper (grep above) | High | UNSAFE |
| 3. Commit, Kafka unavailable | **Stuck document**, HTTP 500 though row is durable, client retry creates a duplicate doc, request thread + DB connection held up to ~60 s (`max.block.ms`) + 120 s (`delivery.timeout.ms`) | `KafkaIngestionProducer.java:23-29`; `GlobalExceptionHandler.java:10-15` | High | UNSAFE |
| 4. Send succeeds, DB commit fails | OK – send only happens after a successful commit, no orphan message | `DocumentService.java:128-133` | n/a | SAFE |
| Consumer before commit / row not visible | OK (cannot happen); a missing row would be retried 2× then DLQ'd | `DocumentService.java:130`; `IngestionService.java:41-42`; `KafkaConfiguration.java:22-31` | Low | SAFE |
| Producer durability (`acks`, idempotence) | OK – defaults are `acks=all`, idempotent | `application.yml:20-24` + client defaults | n/a | SAFE |

**Is a transactional outbox needed? Yes.** The row commit and the Kafka send are two independent writes; any crash or
broker outage between them leaves a committed `PENDING` document with no message and no recovery. An outbox row written
in the upload transaction plus a relay closes the gap. A "re-publish old `PENDING`" sweeper is the weaker alternative and
is only safe once the consumer is idempotent (Audit 2), because `markProcessing()` rejects anything but
`PENDING`/`FAILED` (`java/com/findoc/entity/Document.java:129-134`).

## Audit 2 – Is the ingestion consumer idempotent?

**No.** Failure atomicity is good (one DB transaction), but redelivery, concurrency, deletion, provider failure cost,
schema constraints and poison messages are all unsafe.

| Item | Finding | Evidence | Verdict |
|---|---|---|---|
| (a) Offset commit | `ack-mode: record` is set in `application.yml:31-32`, but the listener factory is hand-built (`new ConcurrentKafkaListenerContainerFactory`, no Boot configurer), so that property may not reach it. Either way the offset is committed after the listener returns, i.e. at-least-once. Effective ack mode **not verified**. | `application.yml:31-33`; `KafkaConfiguration.java:36-45` | UNKNOWN (effective mode); at-least-once is expected |
| (b) Transaction scope | Chunk inserts and the status update share one `@Transactional` `ingest()`. **The Gemini HTTP call is made inside that open transaction**, once per chunk, each with a 10 s read timeout. | `service/document/IngestionService.java:39-69` (embed at :64); `service/embedding/GeminiEmbeddingService.java:37-38` | UNSAFE |
| (c) Unique index on chunks | **None.** Only non-unique indexes exist on `document_chunks` (`tenant_id, document_id`; `tenant_id, document_id, deleted_at`) plus an ivfflat vector index. If a unique index on `(document_id, chunk_index)` is added later it must be partial (`WHERE deleted_at IS NULL`; the column is `deleted_at`, not `deleted`), otherwise re-ingestion after soft delete (soft-delete at `IngestionService.java:59`, then insert at :60-65) will violate it. | `resources/db/changelog/db.changelog-001-init.xml:120-123`, `-004-chunk-soft-delete.xml:12-16`, `-002-vector.xml:23-27` | UNSAFE |
| (d) Redelivery path | `READY`/`PROCESSING` doc → `markProcessing()` throws `IllegalStateException` (treated as a failure, not a no-op). `PENDING`/`FAILED` doc → soft-delete existing live chunks, then re-insert all (delete-then-insert, not upsert). | `IngestionService.java:46,59-65`; `entity/Document.java:129-134` | UNSAFE |
| (e) State machine | `PENDING→PROCESSING` and `FAILED→PROCESSING` (`markProcessing`), `PROCESSING→READY` (`markReady`, `requireStatus`), `PROCESSING/PENDING→FAILED` (`markFailed`, `recordFailure`), `FAILED→PENDING` (`resetForRetry`), non-exhausted `recordFailure` → `PENDING`. Checks are in-memory read-then-write; no `@Version`, no `UPDATE … WHERE status = ?` (grep of entities for `Version`/`DynamicUpdate`: none). | `entity/Document.java:129-168, 206-210` | UNSAFE |
| (f) Concurrency | `setConcurrency` is not called, so one consumer thread per instance. Partition count is not declared in code (no `NewTopic`/`TopicBuilder`), so it is whatever the broker auto-creates (**unknown in production**). Two attempts on one document can still overlap after a rebalance (long in-tx Gemini calls vs `max.poll.interval.ms`) or across instances; nothing serialises them. | `KafkaConfiguration.java:36-45`; grep for `concurrency`/`NewTopic`: none | UNSAFE (overlap proven at service level) |
| (g) Retry tracking | `DefaultErrorHandler` + `ExponentialBackOffWithMaxRetries` (1 s ×2, max 10 s, 2 retries) and a `DeadLetterPublishingRecoverer`. Each retry re-runs the **whole** pipeline including every paid Gemini call. `retry_count` is only incremented at exhaustion (from the DLQ destination resolver), not per retry. | `KafkaConfiguration.java:20-34`; `application.yml:63-71` | UNSAFE |
| (h) Poison messages | No `ErrorHandlingDeserializer`; the value deserializer is a bare `JsonDeserializer`. Observed: `This error handler cannot process 'SerializationException's directly; please consider configuring an 'ErrorHandlingDeserializer'` repeated forever; the topic is wedged. | `application.yml:25-30`; test log | UNSAFE |
| (i) Delete during ingestion | `ingest()` holds a stale `Document` (`deletedAt = null`); the final `documentRepository.save(document)` writes it back. Observed: `deleted_at` is `NULL` after a delete that committed mid-ingestion (document resurrected). Chunks inserted after the delete are not removed (second assertion not reached in the test run). | `IngestionService.java:41,59-68`; `DocumentService.java:96-102` | UNSAFE |

### Failure timelines

| Scenario | Outcome | Evidence | Severity | Verdict |
|---|---|---|---|---|
| 1. Crash after 10 of 20 chunks, before status update | Whole `ingest()` tx rolls back: no partial chunks, doc still `PENDING`, offset not committed → redelivered. (By code reading; a mid-flight failure rollback is asserted in the test for timeline 4.) Cost: the in-tx Gemini calls are repeated. | `IngestionService.java:39-69` | Medium | SAFE (data) |
| 2. Same message delivered twice, sequentially | 2nd delivery throws at `markProcessing()` (`Document must be PENDING or FAILED but was READY`), is retried 2×, then the DLQ destination resolver calls `recordFailure`, which itself throws `Document cannot fail from status READY`. The recoverer never completes, nothing reaches the DLQ, and the record is redelivered forever, **blocking the partition**. Observed 20× and 60× in the test log. | `IngestionService.java:46`; `Document.java:129-134,157-160`; `KafkaConfiguration.java:22-26` | High | UNSAFE |
| 3. Same message delivered twice, concurrently | Both attempts read the doc as `PENDING` (no lock/version) and both insert: **2 live chunks instead of 1** (observed). | `IngestionService.java:41-68`; no unique index | High | UNSAFE |
| 4. Provider fails on chunk 15 of 20 | tx rolls back (no live chunks), doc ends `FAILED` after retries, but every attempt re-embeds from chunk 1: **3 Gemini calls per chunk** (observed `[3,3,…]` for 15 chunks = 45 paid calls, not 15). | `IngestionService.java:55-66`; `KafkaConfiguration.java:27-31` | Medium | UNSAFE (cost) / SAFE (data) |

### Exact duplicate-data / bad-state outcomes (observed unless stated)

- Concurrent delivery of one 1-chunk document → **2 live chunks instead of 1** (for a 20-chunk document: 40 instead of 20).
- `document_chunks` accepts two live rows with the same `(document_id, chunk_index)` – the database offers no last line of defence.
- Document deleted while ingestion is in flight → **`documents.deleted_at` reset to `NULL` (document resurrected)**; live-chunk state for that document was not reached by the assertion.
- Duplicate delivery of a `READY` document → no duplicate chunks, but **the partition is blocked** and all later ingestion messages stay `PENDING`.
- Poison record at offset 0 → **all later ingestion messages stay `PENDING`**.
- Provider failure mid-document → no partial chunks (good), document `FAILED`, **3× embedding calls per chunk**.
- Crash mid-document → no partial chunks (code reading, not simulated).

## Audit 3 – Is dead-letter handling safe, observable and replayable?

Files: `messaging/KafkaConfiguration.java`, `messaging/KafkaIngestionConsumer.java`, `service/document/IngestionService.java`,
`entity/Document.java`, `controller/DocumentController.java`, `resources/application.yml`.

| # | Area | Finding | Verdict |
|---|---|---|---|
| a1 | DLQ payload | Original `IngestionJob(documentId, tenantId, userId)` JSON, same key; Spring default headers (original topic/partition/offset, exception fqcn/message/stacktrace) | SAFE (verified on the wire) |
| a2 | Retry count | No retry-count header on the DLQ record; DLQ routing relies only on `ExponentialBackOffWithMaxRetries(2)` | UNSAFE |
| a3 | Tenant on replay | Payload has `tenantId`+`userId`; `ingest()` loads by `(id, tenantId)` and checks owner. Message itself is unsigned | SAFE (Low risk) |
| b1 | Status on exhaustion | `recordFailure` sets `FAILED` + `errorMessage`, but `retry_count` ends at 1, not the 3 delivery attempts | UNSAFE (Medium) |
| b2 | Atomicity | FAILED is committed in the destination resolver *before* the DLQ send; no outbox/transaction links them | UNSAFE |
| b3 | DLQ publish fails | Redelivery calls `recordFailure` on an already-`FAILED` document → `IllegalStateException` every time; the job can never be dead-lettered and blocks its partition (38 such exceptions logged in the test run) | UNSAFE |
| b5 | Poison pill | Covered by Audit 2 (h) | UNSAFE |
| c | Replay mechanism | None: no endpoint, CLI, job or runbook. `Document.resetForRetry()` is never called | UNSAFE |
| d | Republish to ingestion topic | `FAILED`/`PENDING` doc re-ingests (chunks soft-deleted + re-inserted); `READY` doc wedges the partition (Audit 2 d); concurrent replays race (Audit 2 e) | UNSAFE (covered by Audit 2 tests) |
| e1 | Topic config | DLQ not declared anywhere (no `NewTopic`); partitions/replication/retention unmanaged. Auto-created DLQ has broker defaults | UNSAFE |
| e2 | Partition coupling | DLQ resolver returns `record.partition()`, but spring-kafka falls back to broker partitioning when the DLQ has fewer partitions. A job from partition 2 of a 3-partition source was dead-lettered to a 1-partition DLQ | SAFE (disproved by experiment) |
| e3 | Observability | Actuator exposes only `health,info`; no DLQ metric (no meter name contains `dlq`/`dead`), no alert | UNSAFE |
| f1 | Failure reason visible | `error_msg` stored but `DocumentResponse` has no field for it | UNSAFE |
| f2 | Self re-trigger | No retry endpoint; only re-upload | UNSAFE |

Not testable from code: broker-side DLQ replication factor and retention in production (UNKNOWN; e1 test covers the
declaration gap only).

## Audit 4 – What happens when the same upload is submitted twice?

There is **no dedupe of any kind**: every `POST /api/v1/documents/upload` creates a new document, a new source blob
and a new Kafka job.

| # | Area | Finding | Evidence | Verdict |
|---|---|---|---|---|
| a1 | Idempotency-Key / client id | Controller takes only `@RequestParam("file")`; `TenantJwtFilter`/`MdcTraceFilter` do no dedupe; no interceptors | `DocumentController.java:30,36`, `SecurityConfig.java` | UNSAFE |
| a2 | DB constraints | No uniqueness on `documents` (only `users(tenant_id, username)` and `document_sources.document_id`) | `db.changelog-001-init.xml`, `-003-ingestion.xml` | UNSAFE |
| b | Content hash | No hash/size column; no `(tenant_id, content_hash)` or `(tenant_id, filename)` uniqueness | `Document.java`, changelogs | UNSAFE |
| c1 | Timeout, retry, first succeeded | Retry creates a second document; the first commits regardless of the client | `DocumentService.upload` | UNSAFE |
| c2 | Double click, concurrent | Two documents, two jobs; no lock or constraint | same | UNSAFE |
| c3 | Same file a day later | Same result, indistinguishable from a legitimate re-upload | same | UNSAFE |
| d | Frontend | Input `disabled={uploading}` (no auto-retry, no timeout, no list refresh after failure, generic "Upload failed. Please try again.", sends no key) | `DocumentsPage.tsx`, `documents.ts` | PARTIAL – no Java test (frontend) |
| e1–e2 | Two documents / two ingestion runs | Confirmed | `DocumentService.upload`, `KafkaIngestionConsumer` | UNSAFE |
| e3 | Double embedding calls | Confirmed: `embed()` per chunk, no cache or reuse | `IngestionService.java` | UNSAFE |
| e4 | Duplicate retrieval and citations | Confirmed: `searchSimilar*` filter on tenant, `deleted_at`, document ids only; identical chunks tie on `<=>` | `DocumentChunkRepository.java` | UNSAFE |
| f | Kafka redelivery of a READY doc | No re-embedding (`markProcessing()` rejects READY); noisy failure, covered by Audit 2 | `Document.java` | SAFE for this audit |

Recommended behaviour:

- **Idempotency-Key replay:** same key and same body hash returns the original document (202, `Idempotent-Replayed: true`);
  same key with a different body returns 422; unique `(tenant_id, user_id, key)` arbitrates races; keys expire after ~24 h.
- **Same-content re-upload:** return the existing live document (flag `duplicate: true`) instead of 409 or allowing it;
  re-enqueue it if it is `FAILED`; soft-deleted documents do not count; same filename with different content is allowed.
  Back it with a partial unique index `(tenant_id, content_hash) WHERE deleted_at IS NULL`.

## Tests and the finding each proves

The Audit 4 tests are listed in their own table after the first one below.

All 18 tests of Audits 1–3 assert the desired guarantee and **failed against the production code before fixes**
(Audits 1–2: `10 tests completed, 10 failed`; Audit 3: 8 of 8 failed, see below). They are tagged `correctness`, named
`*IntegrationTest` (so the unit `test` task excludes them), and run on Testcontainers
(`pgvector/pgvector:pg16`, `confluentinc/cp-kafka:7.6.1`).

| Test (class) | Finding it proves | Observed failure before fixes |
|---|---|---|
| `committedPendingDocumentWithoutMessageIsEventuallyIngested` (`UploadPublishAtomicityIntegrationTest`) | A1 timeline 2 / e: no recovery for a committed `PENDING` doc with no message | Still `PENDING` after 60 s |
| `uploadSucceedsWhenBrokerIsUnavailableAfterCommit` (`UploadPublishAtomicityIntegrationTest`) | A1 timeline 3: committed upload reported as failure | `IllegalStateException: broker unavailable` escapes `upload()` |
| `uploadDoesNotBlockRequestThreadOnUnresponsiveBroker` (`UploadPublishAtomicityIntegrationTest`) | A1 (c)/(d): `send().get()` has no timeout and blocks the request thread | Upload took 5.0 s (limit 2 s) |
| `chunkTableRejectsTwoLiveChunksWithTheSameDocumentAndIndex` (`IngestionIdempotencyIntegrationTest`) | A2 (c): no unique index on chunks | Second insert succeeds, no exception |
| `embeddingProviderIsNotCalledInsideAnOpenDatabaseTransaction` (`IngestionIdempotencyIntegrationTest`) | A2 (b): Gemini call inside the open DB tx | Transaction active during `embed` |
| `redeliveredMessageForReadyDocumentIsAcknowledgedAsNoOp` (`IngestionIdempotencyIntegrationTest`) | A2 (d)/(e), timeline 2: duplicate of a `READY` doc is an error that wedges the partition | Follow-up document stays `PENDING` for 30 s; log shows `Document cannot fail from status READY` |
| `concurrentDeliveriesOfSameMessageDoNotDuplicateLiveChunks` (`IngestionIdempotencyIntegrationTest`) | A2 (e)/(f), timeline 3: read-then-write state check, no lock | expected 1 live chunk, was 2 |
| `retryAfterMidwayEmbeddingFailureDoesNotReEmbedCompletedChunks` (`IngestionIdempotencyIntegrationTest`) | A2 (g), timeline 4: retries redo paid calls | 3 embed calls per chunk (limit 1) |
| `documentDeletedDuringIngestionStaysDeletedWithNoLiveChunks` (`IngestionIdempotencyIntegrationTest`) | A2 (i): stale entity write resurrects a deleted document | `deleted_at` is `NULL` |
| `undeserialisableMessageDoesNotBlockSubsequentIngestion` (`PoisonMessageIntegrationTest`) | A2 (h): no `ErrorHandlingDeserializer` | Valid document stays `PENDING`; endless `SerializationException` |
| `deadLetteredMessageCarriesRetryCountHeader` (`DeadLetterContractIntegrationTest`) | A3 a2: no retry-count header | Headers are only `kafka_dlt-*`; none contains "retry" |
| `failedDocumentRecordsTheRealNumberOfDeliveryAttempts` (`DeadLetterContractIntegrationTest`) | A3 b1 | expected 3, was 1 |
| `deadLetteringIsObservableThroughAMetric` (`DeadLetterContractIntegrationTest`) | A3 e3: no DLQ metric | No meter name contains `dlq`/`dead` |
| `ownerCanSeeWhyTheirDocumentFailed` (`DeadLetterContractIntegrationTest`) | A3 f1 | Response JSON has `status: FAILED` and no error/reason field |
| `ownerCanRetriggerIngestionOfTheirFailedDocument` (`DeadLetterContractIntegrationTest`) | A3 c, f2: no retry/replay endpoint | No `@PostMapping` containing "retry" on `DocumentController` |
| `documentIsNotMarkedFailedUntilTheDeadLetterPublishSucceeds` (`DeadLetterPublishAtomicityIntegrationTest`) | A3 b2: status committed before DLQ send | DLQ send fails permanently; document is `FAILED` |
| `jobIsDeadLetteredEvenIfTheFirstDeadLetterPublishFails` (`DeadLetterPublishRecoveryIntegrationTest`) | A3 b3: recovery loop on already-`FAILED` doc | DLQ stays empty for 60 s; log shows `cannot fail from status FAILED` |
| `deadLetterTopicIsDeclaredWithExplicitPartitionsAndRetention` (`DeadLetterTopicProvisioningIntegrationTest`) | A3 e1: DLQ not declared | DLQ topic does not exist at startup |

### Audit 4 tests (`UploadIdempotencyIntegrationTest`)

All 8 failed against the production code before fixes (`8 tests completed, 8 failed`). They use `DocumentService`
and a standalone `MockMvc` around the real `DocumentController`, with the shared Testcontainers stack.

| Test | Finding it proves | Observed failure |
|---|---|---|
| `retryWithSameIdempotencyKeyReturnsOriginalDocument` | A4 a1, c1: key ignored, retry makes a new document | Second response has a different `documentId` |
| `idempotencyKeyReusedWithDifferentContentIsRejected` | A4 a1: key reuse with another payload accepted | Expected 422, got 202 |
| `concurrentIdenticalUploadsCreateASingleDocument` | A4 c2: no lock or constraint under a double click | Expected 1 live document, was 2 |
| `sameContentReuploadedLaterReturnsExistingDocumentWithoutNewIngestion` | A4 c3, e1–e2: re-upload makes a new document and job | Different `documentId` |
| `contentHashIsStoredPerDocument` | A4 b: no hash column | No `*hash*`/`*sha*`/`*checksum*` column on `documents` |
| `databaseEnforcesOneLiveDocumentPerTenantAndContentHash` | A4 a2, b: no unique index | No matching unique index |
| `duplicateUploadDoesNotEmbedTheSameContentTwice` | A4 e3: double Gemini embedding cost | `embed` called 2 times (wanted 1) |
| `duplicateUploadDoesNotProduceDuplicateRetrievalResults` | A4 e4: duplicate hits, so duplicate citations | Hits contain identical chunk content twice |

No test for A4 d (frontend behaviour); the frontend has no Java-reachable surface.

Not covered by a test (verdict SAFE): A3 a1, a3 and e2 (e2 was tested and disproved: the test passed, so it was removed), A1 timelines 1 and 4, consumer-before-commit, producer durability, and A2 timeline 1
(crash mid-document, by code reading only). Not testable from code alone: effective ack mode (a) and production
partition count (f) – these remain UNKNOWN.

### Isolation note

Every `DeadLetter*IntegrationTest` class likewise has its own topics and context (the two publish-failure classes also wrap `KafkaTemplate` via `SpyingKafkaTemplateConfiguration`).

`PoisonMessageIntegrationTest` has its own topic and Spring context on purpose: while the poison record is at the head of
a topic it wedges that consumer permanently and would make every other Kafka-based test fail for the wrong reason.

### Running

```bash
cd findoc-agent
./gradlew correctnessTest        # build.gradle: tag "correctness"; fails until the findings are fixed
```

Docker Engine 29 rejects the Docker API version (1.32) hard-coded in Testcontainers 1.19.8
(`client version 1.32 is too old`). Until Testcontainers is upgraded, run with
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew correctnessTest`.

When the audit tests were originally added, the only non-test file changed was `findoc-agent/build.gradle` (new `correctnessTest` task).

### Implementation update

Audit 1 is now fixed with a Liquibase-managed transactional outbox and scheduled Kafka relay. The three
`UploadPublishAtomicityIntegrationTest` cases pass; the orphan-recovery test now models the actual crash window by
committing a pending outbox event alongside the document. Audits 2–4 and their tests remain pending.
