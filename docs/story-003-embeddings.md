# Story 003: Embeddings and Vector Retrieval

## Status

In progress

## Completed Work

- Added the Gemini embedding boundary using Spring `RestClient` and environment-based configuration.
- Added ten-second connect and read timeouts and validation for the required 768-dimensional response.
- Added a Liquibase migration enabling pgvector, migrating chunk embeddings to `vector(768)`, migrating metadata to JSONB, and creating an IVFFlat cosine index.
- Updated `DocumentChunk` to use the pgvector Java mapping.
- Added tenant-scoped cosine retrieval for all active documents or an explicit document scope.
- Updated agent queries to embed the query and use semantic retrieval while preserving the five-iteration limit.
- Enforced tenant-scoped user lookup during upload.
- Added PostgreSQL BYTEA source persistence so uploads can be processed after the request returns.
- Added Kafka ingestion wiring, PDFBox extraction, Gemini embedding persistence, idempotent chunk replacement, lifecycle transitions, and bounded retry/DLQ recovery.
- Added explicit attempt tracking to ingestion jobs and verified retry republishing plus exhausted-job DLQ routing in focused consumer tests.

## Pending Work

- Validate the Kafka ingestion pipeline against real Kafka and PostgreSQL with pgvector.
- Add focused ingestion tests for upload publication, extraction, embedding failures, idempotency, tenant boundaries, and persistence-level lifecycle behavior.
- Add source IDs and similarity scores to the agent response contract.
- Implement OpenRouter generation, sessions, traces, comparison, MDC tracing, OpenAPI, and Docker Compose in later phases.

## Validation Performed

- `./gradlew clean test --console=plain` passed in `findoc-agent`.
- Clean Java compilation passed after the vector and Gemini changes.
- Existing H2 repository tests and the focused document lifecycle test remain passing; native `<=>` retrieval and BYTEA/Liquibase behavior still require PostgreSQL with pgvector for integration validation.
- Focused `KafkaIngestionConsumerTest` passed for retry republishing and DLQ exhaustion.

## Next Implementation Item

Run PostgreSQL/Kafka integration validation and add focused ingestion service tests before starting OpenRouter generation.