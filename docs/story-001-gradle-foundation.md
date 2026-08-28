# Story 001: Gradle Foundation and Core API Slice

## Status
Complete

## Completed Work

- Created a standalone Gradle Spring Boot 3.2.12 project in `findoc-agent` using the Java 17 toolchain.
- Added Gradle wrapper bootstrap files and centralized dependency configuration.
- Added Spring Boot application configuration with JWT settings, multipart limits, Actuator health, and the five-iteration agent limit.
- Added JWT issuance with `tenant_id` and `user_id` claims.
- Added stateless bearer-token security and tenant/user request context cleanup.
- Added authentication endpoint for the local demo user.
- Added protected document upload, list, status, and delete endpoints.
- Added PDF/text MIME validation and non-empty file validation.
- Added 512-token chunking with a 50-token overlap.
- Added a first agent query endpoint with lookup, compare, summarise, and report intent classification, bounded by a maximum of five iterations.
- Added Java record DTOs for request and response objects.
- Updated the specification build references from Maven to Gradle.
- Added a Spring context smoke test and chunking test.

## Current Limitations

- Documents and chunks are stored in memory; data is lost on restart.
- The upload path marks documents ready immediately and does not publish Kafka ingestion jobs.
- PDF content extraction, Gemini embeddings, pgvector search, OpenRouter generation, sessions, traces, comparison, and explain/history endpoints are not implemented.
- Repository/entity migrations, database tenant predicates, retry/DLQ handling, MDC trace logging, OpenAPI configuration, and circuit-breaker behavior remain pending.
- `setup.sh` was intentionally not changed in this phase; it remains incomplete and does not generate the same Gradle project.

## Validation Performed

- `./gradlew test` passed.
- `./gradlew build` passed.
- Gradle 8.10.2 was downloaded through the project wrapper bootstrap.

## Next Implementation Item

Implement persistent PostgreSQL domain storage and tenant isolation first. Add the Liquibase schema and UUID entities for tenants, users, documents, chunks, sessions, messages, and traces; then add tenant-scoped repositories and replace the in-memory document store. This establishes the data contract required by Kafka ingestion and vector retrieval.
