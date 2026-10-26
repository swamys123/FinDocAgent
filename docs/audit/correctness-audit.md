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
| a2 | Retry count | No retry-count header on the DLQ record; DLQ routing relies only on `ExponentialBackOffWithMaxRetries(2)` | UNSAFE |
| a3 | Tenant on replay | Payload has `tenantId`+`userId`; `ingest()` loads by `(id, tenantId)` and checks owner. Message itself is unsigned | SAFE (Low risk) |
| b1 | Status on exhaustion | Each failed ingestion delivery increments `retry_count`; the final recovery moves `PENDING` to `FAILED` without double-counting. The regression test confirms the configured three delivery attempts. | SAFE |
| b2 | Atomicity | FAILED is committed in the destination resolver *before* the DLQ send; no outbox/transaction links them | UNSAFE |
| b3 | DLQ publish fails | Redelivery calls `recordFailure` on an already-`FAILED` document → `IllegalStateException` every time; the job can never be dead-lettered and blocks its partition (38 such exceptions logged in the test run) | UNSAFE |
| b5 | Poison pill | Covered by Audit 2 (h) | UNSAFE |
| c | Replay mechanism | `POST /api/v1/admin/dlq/{documentId}/replay` identifies the message by document UUID, requires a tenant `ADMIN`, scopes the document lookup to the JWT tenant, and atomically transitions `FAILED` to `PENDING` with one transactional outbox event. A repeated call while `PENDING` is a no-op; `PROCESSING`/`READY` return 409. | SAFE |
| d | Republish to ingestion topic | Replay uses the transactional outbox and original document owner/tenant; conditional status transition prevents duplicate events. `READY` and active `PROCESSING` documents cannot be replayed; the existing consumer acknowledges a `READY` delivery as a no-op. | SAFE |
| e1 | Topic config | DLQ not declared anywhere (no `NewTopic`); partitions/replication/retention unmanaged. Auto-created DLQ has broker defaults | UNSAFE |
| e2 | Partition coupling | DLQ resolver returns `record.partition()`, but spring-kafka falls back to broker partitioning when the DLQ has fewer partitions. A job from partition 2 of a 3-partition source was dead-lettered to a 1-partition DLQ | SAFE (disproved by experiment) |
| e3 | Observability | Actuator exposes only `health,info`; no DLQ metric (no meter name contains `dlq`/`dead`), no alert | UNSAFE |
| f1 | Failure reason visible | `error_msg` stored but `DocumentResponse` has no field for it | UNSAFE |
| f2 | Admin re-trigger | Authorized tenant admins can re-trigger a failed job by document UUID; owner identity and tenant are copied from the stored document, not supplied by the caller. | SAFE |

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
committing a pending outbox event alongside the document.

Audit 2 is implemented: the partial live-chunk index and upsert, tenant-scoped atomic state transitions, external
embedding calls outside transactions with durable staging, reuse of completed embeddings, explicit record-level
acknowledgment, retry attempt tracking, stale-claim recovery through the transactional outbox, and error-handling
deserialization for poison records. Liquibase halts on duplicate live chunks before index creation. Testcontainers
integration tests cover these contracts, including crash-recovery scheduling and poison-message progress. Audit 3
remains pending; Audit 3 finding b1 (accurate retry count) is also resolved by the shared failure transition.

Audit 4's idempotency-key contract is implemented for `POST /api/v1/documents/upload`,
`POST /api/v1/agent/query`, and `POST /api/v1/agent/compare`. The tenant/key primary key arbitrates concurrent
requests. Matching fingerprints replay the stored status and JSON response; a changed fingerprint returns 422.
Keys expire after 24 hours, and requests without `Idempotency-Key` retain the previous behavior. Upload fingerprints
include the filename, content type, and bytes; JSON fingerprints include the endpoint and request fields. Focused
controller and Testcontainers coverage validates replay, mismatch rejection, tenant isolation, concurrent requests,
expiry, and rollback of failed operations.

The remaining Audit 4 work is content-hash deduplication (including a unique live-document index and duplicate
retrieval/embedding coverage) and frontend use of idempotency keys when retry behavior is added. Audit 3 replay is
implemented; retry/DLQ publish atomicity, failure recovery, provisioning, observability, and failure-reason visibility
remain pending.
