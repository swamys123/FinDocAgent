# Implementation Status

This file is the authoritative starting point for implementation work across sessions.

## Current Phase
- Status: Agent generation, session persistence, and audit tracing are being implemented; PostgreSQL/Kafka runtime validation remains the next integration gate.
- Completed: Gradle Spring Boot project, tenant-aware JWT authentication, protected document APIs, tenant-scoped persistence, durable BYTEA upload sources, confirmed Kafka publication, Kafka ingestion wiring, PDF/text extraction with page-count persistence, chunking, vector schema, Gemini response validation, tenant-safe cosine retrieval, bounded agent query flow, session/trace persistence scaffolding, OpenRouter-backed generation fallback, the PostgreSQL BYTEA/OID schema mismatch fix for document sources, and request-scoped MDC tracing for trace_id/tenant_id/user_id.
- Pending: Real PostgreSQL/pgvector and Kafka runtime validation, stronger ingestion tests, remaining API contracts, and broader tests.
- Validation: Focused agent regression tests and the project test suite have passed for the new generation path; PostgreSQL runtime boot validation is being rechecked after the blob mapping fix; the new MDC trace regression is passing after the filter fix.
- Next item: Re-run the app startup against PostgreSQL and continue with Kafka integration validation once the runtime stack is green.

## Stories and Phases

| ID | Handoff | Status | Next item |
| --- | --- | --- | --- |
| Setup | This file | Complete | Register the first implementation story |

| Standards | Repository guidance | Complete | Apply standards to the first implementation story |

| Story 001 | [Gradle foundation and core API slice](story-001-gradle-foundation.md) | Complete | Implement PostgreSQL entities and tenant-scoped repositories |

| Story 002 | [Persistence and tenant isolation](story-002-persistence.md) | Complete for current POC; PostgreSQL validation remains | Maintain tenant-scoped persistence |

| Story 003 | [Embeddings and vector retrieval](story-003-embeddings.md) | In progress | Add ingestion service tests and validate PostgreSQL/Kafka |

| Story 004 | [Agent generation and audit trace](story-004-agent-generation.md) | In progress | Complete API contracts and integration tests |

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
