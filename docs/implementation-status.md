# Implementation Status

This file is the authoritative starting point for implementation work across sessions.

## Current Phase
- Status: Asynchronous ingestion implementation is in progress; PostgreSQL/Kafka runtime validation remains.
- Completed: Gradle Spring Boot project, tenant-aware JWT authentication, protected document APIs, tenant-scoped persistence, durable BYTEA upload sources, Kafka ingestion wiring, PDF/text extraction, chunking, vector schema, Gemini response validation, tenant-safe cosine retrieval, bounded agent query flow, lifecycle validation, retry/DLQ configuration, and focused tests.
- Pending: PostgreSQL/pgvector and Kafka integration validation, stronger ingestion tests, OpenRouter generation, sessions/traces, remaining API contracts, MDC tracing, and broader tests.
- Validation: `./gradlew clean test --console=plain` passed in `findoc-agent`, and `./gradlew bootRun --console=plain` started successfully on port 8080.
- Next item: Connect Gemini embeddings to the asynchronous ingestion pipeline and add PostgreSQL/pgvector integration tests.

## Stories and Phases

| ID | Handoff | Status | Next item |
| --- | --- | --- | --- |
| Setup | This file | Complete | Register the first implementation story |

| Standards | Repository guidance | Complete | Apply standards to the first implementation story |

| Story 001 | [Gradle foundation and core API slice](story-001-gradle-foundation.md) | Complete | Implement PostgreSQL entities and tenant-scoped repositories |

| Story 002 | [Persistence and tenant isolation](story-002-persistence.md) | Complete for current POC; PostgreSQL validation remains | Maintain tenant-scoped persistence |

| Story 003 | [Embeddings and vector retrieval](story-003-embeddings.md) | In progress | Validate asynchronous ingestion against PostgreSQL and Kafka |

| Story 004 | Planned: agent retrieval and audit trace | Planned | Complete API contracts and integration tests |

| Story 005 | [Developer runbook and endpoint validation](story-005-dev-runbook.md) | Complete | Keep the runbook aligned with evolving API and infrastructure changes |

## Priority Order

1. PostgreSQL/Liquibase schema, UUID entities, tenant-scoped repositories, and replacement of in-memory storage. (POC complete; real-Postgres integration validation remains.)
2. Kafka ingestion with PDFBox extraction, chunk persistence, Gemini embeddings, retry handling, and DLQ behavior.
3. pgvector cosine retrieval, OpenRouter report generation, agent tools, sessions, traces, and the five-iteration guard.
4. Remaining controllers, MDC trace logging, OpenAPI, circuit-breaker behavior, and tenant/security/integration tests.

## Handoff Format

Each implementation handoff under `docs/` must record:

- Completed work
- Pending work
- Validation performed
- Next implementation item
