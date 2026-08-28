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

## Pending Work

- Reconnect the embedding service to the Kafka ingestion pipeline once the ingestion source is restored in this checkout.
- Add idempotent chunk replacement, failure transitions, retry/DLQ send confirmation, and integration tests against PostgreSQL with pgvector.
- Add source IDs and similarity scores to the agent response contract.
- Implement OpenRouter generation, sessions, traces, comparison, MDC tracing, OpenAPI, and Docker Compose in later phases.

## Validation Performed

- `./gradlew clean test --console=plain` passed in `findoc-agent`.
- Clean Java compilation passed after the vector and Gemini changes.
- Existing H2 repository tests remain passing; native `<=>` retrieval still requires PostgreSQL with pgvector for integration validation.

## Next Implementation Item

Restore or implement the asynchronous ingestion pipeline and generate one Gemini embedding per persisted chunk before marking a document `READY`.