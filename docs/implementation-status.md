# Implementation Status

This file is the authoritative starting point for implementation work across sessions.

## Current Phase
- Status: Upload, ingestion, chunking, embedding, vector retrieval, structured agent query sources, session history, explain traces, and document comparison are implemented. Local dotenv loading is available for Gradle runtime and test tasks; the next step is live infrastructure and external-generation validation with a populated local `.env`.
- Completed: Gradle Spring Boot project, tenant-aware JWT authentication, protected document APIs, tenant-scoped persistence, durable BYTEA upload sources, confirmed Kafka publication, Kafka ingestion wiring, PDF/text extraction with page-count persistence, chunking, vector schema, Gemini response validation, the Gemini model-contract fix for the unsupported default model, tenant-safe cosine retrieval, bounded agent query flow, session/trace persistence, structured query citations, OpenRouter-backed generation with structured comparison fallback, tenant-safe document comparison, the PostgreSQL BYTEA/OID schema mismatch fix for document sources, request-scoped MDC tracing for trace_id/tenant_id/user_id, original-file retrieval support via the document download endpoint, daily log rollover with dated archive naming and no deletion of historical logs, the pgvector Hibernate mapping fix for chunk embedding persistence (`float[]` with `SqlTypes.VECTOR` + `hibernate-vector`), and local dotenv configuration with a non-secret template and Copilot secret-handling safeguards.
- Pending: Real PostgreSQL/pgvector, Kafka, and OpenRouter runtime validation; agent web-layer contract coverage; stronger ingestion tests; remaining OpenAPI and circuit-breaker work.
- Validation: The Logback rollover change compiles cleanly, and the project test suite passed successfully after the update. Runtime archive creation at the next day boundary still needs a live restart across a date change.
- Next item: Restart the app on a new day boundary and confirm the archived file naming format in the logs directory.

## Stories and Phases

| ID | Handoff | Status | Next item |
| --- | --- | --- | --- |
| Setup | This file | Complete | Register the first implementation story |

| Standards | Repository guidance | Complete | Apply standards to the first implementation story |

| Story 001 | [Gradle foundation and core API slice](story-001-gradle-foundation.md) | Complete | Implement PostgreSQL entities and tenant-scoped repositories |

| Story 002 | [Persistence and tenant isolation](story-002-persistence.md) | Complete for current POC; PostgreSQL validation remains | Maintain tenant-scoped persistence |

| Story 003 | [Embeddings and vector retrieval](story-003-embeddings.md) | In progress | Add ingestion service tests and validate PostgreSQL/Kafka |

| Story 004 | [Agent generation and audit trace](story-004-agent-generation.md) | In progress | Add API contract tests and validate against live infrastructure |

| Story 005 | [Developer runbook and endpoint validation](story-005-dev-runbook.md) | Complete | Keep the runbook aligned with evolving API and infrastructure changes |

| Story 006 | [File-based troubleshooting logging](story-006-file-logging.md) | Complete | Keep the log location aligned with the runtime environment |

| Story 007 | [Local dotenv configuration](story-007-local-dotenv-configuration.md) | Complete | Validate a populated `.env` against the live PostgreSQL/Kafka stack |

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
