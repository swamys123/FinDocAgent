# Story 003: Embeddings and Vector Retrieval

## Status

Complete for local integration validation; provider validation remains

## Completed Work

- Fixed the Gemini embedding regression by replacing the unsupported default model with the current model name and adding the required 768-dimensional request contract.
- Added the Gemini embedding boundary using Spring `RestClient` and environment-based configuration.
- Added ten-second connect and read timeouts and validation for the required 768-dimensional response.
- Added a Liquibase migration enabling pgvector, migrating chunk embeddings to `vector(768)`, migrating metadata to JSONB, and creating an IVFFlat cosine index.
- Updated `DocumentChunk` to use the pgvector Java mapping.
- Added tenant-scoped cosine retrieval for all active documents or an explicit document scope.
- Updated agent queries to embed the query and use semantic retrieval while preserving the five-iteration limit.
- Enforced tenant-scoped user lookup during upload.
- Added PostgreSQL BYTEA source persistence so uploads can be processed after the request returns.
- Fixed the PostgreSQL schema mismatch by aligning the DocumentSource blob mapping with the BYTEA column type used in Liquibase.
- Added Kafka ingestion wiring, PDFBox extraction, Gemini embedding persistence, idempotent chunk replacement, lifecycle transitions, and bounded retry/DLQ recovery.
- Consolidated ingestion on one `IngestionJob` listener and Spring Kafka error-handler path. The handler now applies two configurable exponential retries after the initial delivery before terminal failure recording and DLQ publication.
- Removed the obsolete file-path ingestion listener and its parallel message contract, eliminating conflicting topic consumption and application-managed attempt metadata.
- Added document-chunk soft deletion with a Liquibase migration. Document deletion now soft-deletes tenant-scoped chunks, and chunk listing, counts, ingestion replacement, and cosine searches exclude deleted chunks.
- Persisted extracted PDF page counts during successful ingestion.
- Added focused ingestion tests for successful lifecycle processing, page-count persistence, owner isolation, and embedding failures.
- Fixed the pgvector `bytea`/`vector` insert mismatch by switching `DocumentChunk.embedding` from `PGvector` with `SqlTypes.OTHER` to native `float[]` with `SqlTypes.VECTOR`, `@Array(length = 768)`, and the `hibernate-vector` module. Removed the `PGvector` wrapper from the ingestion path while keeping `PGvector` for native similarity query parameters.
- Added a focused regression test asserting chunk embeddings are persisted as native `float[]` values.

## Pending Work

- Validate the Kafka ingestion pipeline against real Kafka and PostgreSQL with pgvector.
- Add remaining focused upload-service, idempotency, extraction-failure, and persistence-level lifecycle tests.
- Validate the Kafka ingestion pipeline against real Kafka and PostgreSQL with pgvector.
- Add remaining focused upload-service, idempotency, extraction-failure, and persistence-level lifecycle tests.
- Complete provider failure-path tests and live Gemini validation.

## Validation Performed

- `./gradlew clean test --console=plain` passed in `findoc-agent`.
- Clean Java compilation passed after the vector and Gemini changes.
- Existing H2 repository tests and the focused document lifecycle test remain passing; native `<=>` retrieval and BYTEA/Liquibase behavior still require PostgreSQL with pgvector for integration validation.
- `./gradlew integrationTest --console=plain` could not start Testcontainers because the Podman remote socket is unavailable; PostgreSQL/pgvector and Kafka workflow validation remains pending.
- Focused messaging and document-ingestion tests passed, including producer confirmation, listener propagation, lifecycle processing, page-count persistence, owner isolation, and embedding failure behavior.
- Full test suite passed after the pgvector `float[]` mapping fix; the new `persistsChunkEmbeddingAsNativeFloatArray` regression confirms embedding values reach the repository as native `float[]`.
- `./gradlew test --tests com.findoc.service.document.DocumentServiceTest --console=plain` and `./gradlew test --tests com.findoc.messaging.KafkaIngestionConsumerTest --console=plain` passed after chunk soft-delete and retry-path consolidation.
- `./gradlew test --console=plain` passed after the combined agent, persistence, and ingestion updates.
- `./gradlew localIntegrationTest --console=plain` passed against local PostgreSQL/pgvector and Kafka. The suite validates Liquibase, native `<=>` cosine retrieval, tenant isolation, soft-deleted chunk exclusion, Kafka JSON delivery, BYTEA source extraction, chunk persistence, and READY lifecycle completion with deterministic test embeddings.

## Next Implementation Item

Complete provider failure-path coverage and live Gemini validation.