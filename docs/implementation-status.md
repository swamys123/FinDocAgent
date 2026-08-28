# Implementation Status

This file is the authoritative starting point for implementation work across sessions.

## Current Phase
- Status: Persistence foundation is implemented; the next step is full document lifecycle validation and the Kafka/vector ingestion story.
- Completed: Gradle Spring Boot project, JWT tenant claims, protected document APIs, in-memory tenant ownership, chunking, bounded agent query flow, specification Gradle alignment, JPA/PostgreSQL foundation, Liquibase schema bootstrap, tenant-aware repositories, and repository-backed auth/document logic.
- Pending: Full Postgres-backed document lifecycle, Kafka ingestion, pgvector/Gemini retrieval, OpenRouter generation, sessions/traces, remaining API contracts, MDC tracing, and broader tests.
- Validation: `./gradlew test --console=plain` passed in `findoc-agent` with exit code 0.
- Next item: Validate the full document lifecycle against a real Postgres-backed environment, then proceed to Story 003 Kafka ingestion.

## Stories and Phases

| ID | Handoff | Status | Next item |
| --- | --- | --- | --- |
| Setup | This file | Complete | Register the first implementation story |

| Standards | Repository guidance | Complete | Apply standards to the first implementation story |

| Story 001 | [Gradle foundation and core API slice](story-001-gradle-foundation.md) | Complete | Implement PostgreSQL entities and tenant-scoped repositories |

| Story 002 | [Persistence and tenant isolation](story-002-persistence.md) | In progress | Validate the full document lifecycle against Postgres and transition into Kafka ingestion |

| Story 003 | Planned: asynchronous ingestion and embeddings | Planned | Add vector retrieval and agent tools |

| Story 004 | Planned: agent retrieval and audit trace | Planned | Complete API contracts and integration tests |

## Priority Order

1. PostgreSQL/Liquibase schema, UUID entities, tenant-scoped repositories, and replacement of in-memory storage.
2. Kafka ingestion with PDFBox extraction, chunk persistence, Gemini embeddings, retry handling, and DLQ behavior.
3. pgvector cosine retrieval, OpenRouter report generation, agent tools, sessions, traces, and the five-iteration guard.
4. Remaining controllers, MDC trace logging, OpenAPI, circuit-breaker behavior, and tenant/security/integration tests.

## Handoff Format

Each implementation handoff under `docs/` must record:

- Completed work
- Pending work
- Validation performed
- Next implementation item
