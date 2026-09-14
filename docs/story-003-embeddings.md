# Story 003: Embeddings and Vector Retrieval

## Status

Complete

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
- Added comprehensive failure-path tests for `GeminiEmbeddingService` covering blank API keys, invalid embedding dimension payloads, non-numeric values, HTTP 4xx (429/401), HTTP 5xx (503/500), and circuit-breaker tripping.
- Added `LiveProviderIntegrationTest` executing live Gemini embedding calls when `GEMINI_API_KEY` is present, asserting 768 dimensions and semantic similarity ordering.

## Pending Work

- Validate the containerized Testcontainers workflow once the remote Podman API socket is available.

## Validation Performed

- `./gradlew clean test --console=plain` passed in `findoc-agent`.
- Clean Java compilation passed after vector, Gemini, and resilience changes.
- Existing H2 repository tests and the focused document lifecycle test remain passing.
- `./gradlew test --tests com.findoc.service.embedding.GeminiEmbeddingServiceTest --console=plain` and `./gradlew test --tests com.findoc.service.ProviderCircuitBreakerTest --console=plain` passed with all failure path scenarios.
- `./gradlew test --console=plain` passed all unit tests.
- `./gradlew localIntegrationTest --console=plain` passed against local PostgreSQL/pgvector and Kafka, running `LiveProviderIntegrationTest`, `LocalKafkaIngestionIntegrationTest`, and `LocalPostgresPgvectorIntegrationTest`.

## Next Implementation Item

Validate full end-to-end multi-turn chat sessions with real provider responses.