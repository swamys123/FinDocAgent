# Distributed-systems correctness audit

Scope (Audits 1–4): initial read-only audit of the upload → Kafka → ingestion pipeline (Spring Boot 3.2.12,
spring-kafka 3.1.10, kafka-clients 3.6.2), followed by fixes tracked in the implementation updates below. Every
originally UNSAFE finding is backed by a failing test in
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

**Yes, for the audited delivery, concurrency, failure-cost, deletion and poison-message cases.** Successful chunk
embeddings are durably staged outside the live-chunk transaction, state transitions are conditional, and stale
processing claims are recovered through the transactional outbox.

| Item | Finding | Evidence | Verdict |
|---|---|---|---|
| (a) Offset commit | The hand-built listener factory explicitly sets `ContainerProperties.AckMode.RECORD`, so listener success is committed per record; failures remain under the configured error handler. | `messaging/KafkaConfiguration.java`; `application.yml` | SAFE (at-least-once) |
| (b) Transaction scope | The provider call runs in the non-transactional ingestion orchestrator. Each successful embedding is committed to a staging table in a short transaction, and staged chunks are copied into live rows with `READY` completion in one final transaction. | `service/document/IngestionService.java`; `service/document/IngestionProgressService.java`; `service/document/IngestionCompletionService.java` | SAFE |
| (c) Unique index on chunks | A Liquibase partial unique index now enforces one live row per `(document_id, chunk_index)` with `WHERE deleted_at IS NULL`; a migration precondition halts if pre-existing live duplicates would prevent index creation. | `resources/db/changelog/db.changelog-007-ingestion-idempotency.xml`; `IngestionIdempotencyIntegrationTest` | SAFE |
| (d) Redelivery path | Chunk writes now use `INSERT … ON CONFLICT` against the partial index, and only stale trailing chunk indexes are soft-deleted after successful writes. An already-`READY` document is acknowledged as a no-op. | `repository/DocumentChunkRepository.java`; `service/document/IngestionService.java`; `IngestionIdempotencyIntegrationTest` | SAFE |
| (e) State machine | Tenant-scoped conditional SQL updates implement eligible claims, completion, and failure transitions. A fresh `PROCESSING` claim remains an error; only `READY` is a no-op. A lease-based recovery transaction resets stale `PROCESSING` claims and writes a new outbox event atomically. | `repository/DocumentRepository.java`; `repository/StaleIngestionRepository.java`; `service/document/StaleIngestionRecoveryService.java` | SAFE |
| (f) Concurrency | Atomic claims prevent overlapping work for a document. Successful staging updates the processing heartbeat; only claims older than the configured lease are recovered and re-enqueued. | `repository/DocumentRepository.java`; `repository/IngestionProgressRepository.java`; `messaging/StaleIngestionRecovery.java` | SAFE (document scope) |
| (g) Retry tracking | Successful embeddings are durably staged and reused on redelivery. A failed provider call is retried normally; each failed delivery increments `retry_count` once, and final DLQ recovery does not double-count. Stale crashed attempts are counted when re-enqueued. | `repository/IngestionProgressRepository.java`; `repository/DocumentRepository.java`; `KafkaConfiguration.java`; `IngestionIdempotencyIntegrationTest`; `DeadLetterContractIntegrationTest` | SAFE |
| (h) Poison messages | `ErrorHandlingDeserializer` wraps the trusted `JsonDeserializer`; the error handler recovers malformed records to the DLQ, and a test proves a valid message behind a poison record reaches `READY`. | `application.yml`; `CorrectnessTestSupport`; `PoisonMessageIntegrationTest` | SAFE |
| (i) Delete during ingestion | Chunk upserts require the same tenant-scoped document to remain non-deleted; completion requires `status = 'PROCESSING'` and `deleted_at IS NULL`. If deletion wins, ingestion aborts and its chunk transaction rolls back rather than saving a stale `Document`. | `repository/DocumentChunkRepository.java`; `repository/DocumentRepository.java`; `IngestionIdempotencyIntegrationTest` | SAFE |

### Failure timelines

| Scenario | Outcome | Evidence | Severity | Verdict |
|---|---|---|---|---|
| 1. Crash after 10 of 20 chunks, before final commit | Live-chunk finalization is atomic. The ten successful embeddings remain staged, the document remains `PROCESSING`, and lease recovery eventually resets it to `PENDING` and enqueues a replay; that replay reuses the staged embeddings. | `IngestionProgressRepository.java`; `StaleIngestionRecovery.java`; `StaleIngestionRecoveryService.java` | Medium | SAFE (recovery after configured lease) |
| 2. Same message delivered twice, sequentially | A `READY` document is acknowledged as a no-op; the record does not reach retries or DLQ. | `IngestionIdempotencyIntegrationTest` | n/a | SAFE |
| 3. Same message delivered twice, concurrently | Only one conditional claim succeeds. The other fresh `PROCESSING` claim is an error and cannot mutate the active attempt; live uniqueness is a database backstop. | `DocumentRepository.java`; `IngestionIdempotencyIntegrationTest` | n/a | SAFE |
| 4. Provider fails on chunk 15 of 20 | The first fourteen successful embeddings stay staged and are reused. The failing chunk is called once per delivery attempt; after exhaustion, no live chunks were committed and the document is `FAILED`. | `IngestionIdempotencyIntegrationTest` | n/a | SAFE (data and completed-call cost) |

### Exact duplicate-data / bad-state outcomes observed before fixes (unless stated)

- Concurrent delivery of one 1-chunk document → **2 live chunks instead of 1** (for a 20-chunk document: 40 instead of 20).
- `document_chunks` accepts two live rows with the same `(document_id, chunk_index)` – the database offers no last line of defence.
- Document deleted while ingestion is in flight → **`documents.deleted_at` reset to `NULL` (document resurrected)**; live-chunk state for that document was not reached by the assertion.
- Duplicate delivery of a `READY` document → no duplicate chunks, but **the partition is blocked** and all later ingestion messages stay `PENDING`.
- Poison record at offset 0 → **all later ingestion messages stay `PENDING`**.
- Provider failure mid-document → no partial chunks (good), document `FAILED`, **3× embedding calls per chunk**.
- Crash mid-document → no partial chunks (code reading, not simulated).

## Audit 3 – Is dead-letter handling safe, observable and replayable?

Files: `messaging/KafkaConfiguration.java`, `messaging/KafkaIngestionConsumer.java`, `service/document/IngestionService.java`,
`service/document/DlqReplayService.java`, `entity/Document.java`, `controller/AdminDlqReplayController.java`,
`config/SecurityConfig.java`, `resources/application.yml`.

| # | Area | Finding | Verdict |
|---|---|---|---|
| a1 | DLQ payload | Original `IngestionJob(documentId, tenantId, userId)` JSON, same key; Spring default headers (original topic/partition/offset, exception fqcn/message/stacktrace) | SAFE (verified on the wire) |
| a2 | Retry count | The DLQ record includes `findoc-retry-count`, populated from the tenant-scoped persisted delivery count; the contract test checks its value is 3 for the configured 3 attempts. | SAFE |
| a3 | Tenant on replay | Payload has `tenantId`+`userId`; `ingest()` loads by `(id, tenantId)` and checks owner. Message itself is unsigned | SAFE (Low risk) |
| b1 | Status on exhaustion | Each failed ingestion delivery increments `retry_count`; the final recovery moves `PENDING` to `FAILED` without double-counting. The regression test confirms the configured three delivery attempts. | SAFE |
| b2 | Atomicity | The recoverer waits for the DLQ send result and only then marks the tenant-scoped document `FAILED`; a failed send leaves the source record eligible for retry. | SAFE (at-least-once; duplicate DLQ records remain possible across a crash after send) |
| b3 | DLQ publish fails | Failed DLQ publication propagates to the error handler without committing the source offset or finalizing the document; the recovery test verifies a subsequent publish succeeds. | SAFE (verified for transient failure) |
| b5 | Poison pill | Covered by Audit 2 (h); the error-handling deserializer routes malformed payloads to the DLQ and allows later valid records to proceed. | SAFE |
| c | Replay mechanism | `POST /api/v1/admin/dlq/{documentId}/replay` identifies the message by document UUID, requires a tenant `ADMIN`, scopes the document lookup to the JWT tenant, and atomically transitions `FAILED` to `PENDING` with one transactional outbox event. A repeated call while `PENDING` is a no-op; `PROCESSING`/`READY` return 409. | SAFE |
| d | Republish to ingestion topic | Replay uses the transactional outbox and original document owner/tenant; conditional status transition prevents duplicate events. `READY` and active `PROCESSING` documents cannot be replayed; the existing consumer acknowledges a `READY` delivery as a no-op. | SAFE |
| e1 | Topic config | A `NewTopic` bean provisions the DLQ with configurable partition count, replication factor, and explicit retention (`findoc.ingestion.dlq-*` settings; defaults: 3, 1, 7 days). The Testcontainers check verifies partitions and topic-level retention. | SAFE (configuration verified; production broker permissions and effective settings remain environment-dependent) |
| e2 | Partition coupling | DLQ resolver returns `record.partition()`, but spring-kafka falls back to broker partitioning when the DLQ has fewer partitions. A job from partition 2 of a 3-partition source was dead-lettered to a 1-partition DLQ | SAFE (disproved by experiment) |
| e3 | Observability | Successful DLQ publishes increment `findoc.ingestion.dlq.published`; Actuator exposes the `metrics` endpoint in addition to health/info. | SAFE (metric registration/increment tested; alerting is deployment configuration) |
| f1 | Failure reason visible | The tenant-scoped `DocumentResponse` includes the underlying failure reason as `errorMessage`. | SAFE |
| f2 | Admin re-trigger | Authorized tenant admins can re-trigger a failed job by document UUID; owner identity and tenant are copied from the stored document, not supplied by the caller. | SAFE |

Not testable from code: production broker authorization, effective replication factor, retention overrides, and alert
configuration (UNKNOWN; application topic declaration and retention behavior are verified with Testcontainers).

## Audit 4 – What happens when the same upload is submitted twice?

The original baseline had no upload deduplication. The current implementation combines tenant-scoped HTTP
idempotency keys with tenant-scoped SHA-256 content deduplication. Identical active content resolves to its existing
document; a failed existing document is queued for retry, and a soft-deleted one does not block a replacement.

| # | Area | Finding | Evidence | Verdict |
|---|---|---|---|---|
| a1 | Idempotency-Key / request replay | Upload supports same-key/same-fingerprint response replay, different-fingerprint 422, and tenant isolation; frontend sends a stable key for a manual retry of the same selected file. | `DocumentController.java`, `IdempotencyExecutor`, `DocumentsPage.tsx`, `UploadIdempotencyIntegrationTest` | SAFE (frontend component behavior has no dedicated test runner) |
| a2 | DB constraints | Partial unique index enforces one active `(tenant_id, content_hash)` row; an advisory transaction lock serializes same-tenant concurrent uploads before the duplicate lookup. | `db.changelog-009-document-content-hash.xml`, `DocumentDeduplicationRepository.java` | SAFE |
| b | Content hash | Upload stores SHA-256 of source bytes; migration backfills existing source blobs and halts if existing active duplicates would violate the new index. | `Document.java`, `DocumentService.java`, changelog 009 | SAFE |
| c1 | Timeout/retry after first success | Same key replays the original response; identical content without a key resolves to the existing document. | `UploadIdempotencyIntegrationTest` | SAFE |
| c2 | Double click/concurrent | Concurrent identical content uploads result in one active document. | `UploadIdempotencyIntegrationTest.concurrentIdenticalUploadsCreateASingleDocument` | SAFE |
| c3 | Same file re-uploaded later | Same-tenant active content returns the original document; soft-deleted content can create a replacement; a FAILED document is re-enqueued. | `UploadIdempotencyIntegrationTest` | SAFE |
| d | Frontend | Manual retry retains the selected `File` and same idempotency key; choosing another file starts a new attempt/key. | `DocumentsPage.tsx`, `documents.ts` | IMPLEMENTED (scoped lint/type-check pass; no frontend test framework) |
| e1–e2 | Duplicate documents / ingestion runs | Hash deduplication creates no second document/source/outbox event for an identical active upload. | `DocumentService.java`, correctness tests | SAFE |
| e3 | Duplicate embedding calls | Duplicate upload integration test requires the document to reach READY and verifies exactly one matching embedding call. | `UploadIdempotencyIntegrationTest` | SAFE |
| e4 | Duplicate retrieval and citations | Duplicate-upload retrieval test requires READY and non-empty results, then checks for unique chunk contents. | `UploadIdempotencyIntegrationTest` | SAFE |
| f | Kafka redelivery of a READY doc | READY delivery is acknowledged as a no-op without re-embedding. | `IngestionIdempotencyIntegrationTest` | SAFE |

Recommended behaviour:

- **Idempotency-Key replay:** same key and same body hash returns the original document (202, `Idempotent-Replayed: true`);
  same key with a different body returns 422; unique `(tenant_id, user_id, key)` arbitrates races; keys expire after ~24 h.
- **Same-content re-upload:** return the existing live document (flag `duplicate: true`) instead of 409 or allowing it;
  re-enqueue it if it is `FAILED`; soft-deleted documents do not count; same filename with different content is allowed.
  Back it with a partial unique index `(tenant_id, content_hash) WHERE deleted_at IS NULL`.

## Tests and the finding each proves

The Audit 4 tests are listed in their own table after the first one below.

The original 18 tests of Audits 1–3 assert the desired guarantee and **failed against the production code before fixes**
(Audits 1–2: `10 tests completed, 10 failed`; Audit 3: 8 of 8 failed, see below). They are tagged `correctness`, named
`*IntegrationTest` (so the unit `test` task excludes them), and run on Testcontainers
(`pgvector/pgvector:pg16`, `confluentinc/cp-kafka:7.6.1`).

Current verification: `correctnessTest` passed all 38 tests after the Audit 3/Audit 4 implementations and test-isolation
fixes. The correctness fixtures reset their shared embedding mock before every test and close each class context after
the class, preventing topic-specific listeners/mocks from leaking between classes.

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
| `adminReplayPreservesTenantAndOwnerAndReingestsDocument` (`DeadLetterContractIntegrationTest`) | A3 c, d, f2: authorized replay, tenant/owner preservation, and status progression | Before the fix, no authorized admin replay endpoint or transactional replay path existed |
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

No automated component-level test for A4 d (frontend behaviour); the repository does not currently have a frontend test framework.

Additional regression cases now verify tenant isolation for content dedupe, soft-delete replacement, failed-document
requeue, persisted SHA-256 values, successful embedding/retrieval, and exact retry metadata/recovery behavior.

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
./gradlew correctnessTest        # build.gradle: tag "correctness"
```

Docker Engine 29 rejects the Docker API version (1.32) hard-coded in Testcontainers 1.19.8
(`client version 1.32 is too old`). Until Testcontainers is upgraded, run with
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew correctnessTest`.

### Implementation update

Audit 1 is now fixed with a Liquibase-managed transactional outbox and scheduled Kafka relay. The three
`UploadPublishAtomicityIntegrationTest` cases pass; the orphan-recovery test now models the actual crash window by
committing a pending outbox event alongside the document.

Audit 2 is implemented: the partial live-chunk index and upsert, tenant-scoped atomic state transitions, external
embedding calls outside transactions with durable staging, reuse of completed embeddings, explicit record-level
acknowledgment, retry attempt tracking, stale-claim recovery through the transactional outbox, and error-handling
deserialization for poison records. Liquibase halts on duplicate live chunks before index creation. Testcontainers
integration tests cover these contracts, including crash-recovery scheduling and poison-message progress.

Audit 4's idempotency-key contract is implemented for `POST /api/v1/documents/upload`,
`POST /api/v1/agent/query`, and `POST /api/v1/agent/compare`. The tenant/key primary key arbitrates concurrent
requests. Matching fingerprints replay the stored status and JSON response; a changed fingerprint returns 422.
Keys expire after 24 hours, and requests without `Idempotency-Key` retain the previous behavior. Upload fingerprints
include the filename, content type, and bytes; JSON fingerprints include the endpoint and request fields. Focused
controller and Testcontainers coverage validates replay, mismatch rejection, tenant isolation, concurrent requests,
expiry, and rollback of failed operations.

Audit 3 now carries persisted retry counts in DLQ headers, waits for successful DLQ publication before finalizing the
document, retries after transient publish failures, provisions the DLQ with configurable partitions/replication/
retention, increments a published-job metric, exposes the metrics endpoint, and includes tenant-scoped failure reasons
in document status responses. Audit 4 now stores/backfills SHA-256 hashes, prevents duplicate active content within a
tenant, requeues failed duplicates, and sends a stable idempotency key for manual frontend retries. All 38 tagged
correctness tests passed. Production Kafka configuration/permissions and frontend component-level tests remain
environmental/coverage follow-ups rather than implementation failures.
