# Implementation Status

This file is the authoritative starting point for implementation work across sessions.

## Current Phase
- Status: Upload, ingestion, chunking, embedding, vector retrieval, structured agent query sources, session history, explain traces, document comparison, provider resilience, local dotenv loading, public-documentation alignment, and root-project onboarding documentation are implemented or in progress. The immediate execution gate is live PostgreSQL/pgvector and Kafka workflow validation with a populated local `.env`.
- Completed: Gradle Spring Boot project, tenant-aware JWT authentication, protected document APIs, tenant-scoped persistence, durable BYTEA upload sources, confirmed Kafka publication, Kafka ingestion wiring, PDF/text extraction with page-count persistence, chunking, vector schema, Gemini response validation, the Gemini model-contract fix for the unsupported default model, tenant-safe cosine retrieval, bounded agent query flow, session/trace persistence, structured query citations, OpenRouter-backed generation with structured comparison fallback, tenant-safe document comparison, the PostgreSQL BYTEA/OID schema mismatch fix for document sources, request-scoped MDC tracing for trace_id/tenant_id/user_id, original-file retrieval support via the document download endpoint, daily log rollover with dated archive naming and no deletion of historical logs, the pgvector Hibernate mapping fix for chunk embedding persistence (`float[]` with `SqlTypes.VECTOR` + `hibernate-vector`), local dotenv configuration with a non-secret template and Copilot secret-handling safeguards, and the shared provider circuit-breaker implementation with configurable thresholds and OpenRouter fallback behavior.
- Pending: Live PostgreSQL/pgvector and Kafka workflow validation; live Gemini/OpenRouter validation; controller/API contract coverage; stronger ingestion and persistence-level tests; provider resilience failure-path coverage; OpenAPI and any required circuit-breaker operational metrics.
- Validation: `./gradlew compileJava --console=plain`, focused provider tests, and `./gradlew clean test --console=plain` passed. `./gradlew integrationTest --console=plain` is currently blocked during Testcontainers startup because the Podman remote socket is unavailable (`podman info` reports `RemoteSocket.Exists=false`). Live provider validation remains pending. Runtime log archive creation at a date boundary remains an optional follow-up, not the next implementation item.
- Next item: Run the full unit suite, then execute PostgreSQL/pgvector and Kafka workflow validation; add controller contract tests and complete provider failure-path coverage from the results.

## Stories and Phases

| ID | Handoff | Status | Next item |
| --- | --- | --- | --- |
| Setup | This file | Complete | Register the first implementation story |

| Standards | Repository guidance | Complete | Apply standards to the first implementation story |

| Story 001 | [Gradle foundation and core API slice](story-001-gradle-foundation.md) | Complete | Implement PostgreSQL entities and tenant-scoped repositories |

| Story 002 | [Persistence and tenant isolation](story-002-persistence.md) | Complete for current POC; PostgreSQL validation remains | Maintain tenant-scoped persistence |

| Story 003 | [Embeddings and vector retrieval](story-003-embeddings.md) | In progress | Validate PostgreSQL/pgvector and Kafka workflows |

| Story 004 | [Agent generation and audit trace](story-004-agent-generation.md) | In progress | Complete provider resilience tests, API contract tests, and live validation |

| Story 005 | [Developer runbook and endpoint validation](story-005-dev-runbook.md) | Complete | Keep the runbook aligned with evolving API and infrastructure changes |

| Story 006 | [File-based troubleshooting logging](story-006-file-logging.md) | Complete | Keep the log location aligned with the runtime environment |

| Story 007 | [Local dotenv configuration](story-007-local-dotenv-configuration.md) | Complete | Validate a populated `.env` against the live PostgreSQL/Kafka stack |

| Story 008 | [Root README onboarding](story-008-root-readme-onboarding.md) | Complete | Keep setup and feature documentation aligned with implementation changes |

| Story 009 | [Public documentation alignment](story-009-public-documentation-alignment.md) | Complete | Complete live infrastructure and provider validation |

## Priority Order

1. Validate PostgreSQL/Liquibase schema, pgvector persistence/retrieval, Kafka ingestion, retry/DLQ behavior, and tenant isolation against the real local stack.
2. Complete provider resilience failure-path tests and validate Gemini/OpenRouter behavior with injected credentials.
3. Add web-layer contract tests for document and agent endpoints, including validation, authentication, tenant scope, and response shapes.
4. Add OpenAPI documentation and operational circuit-breaker metrics if required by deployment consumers.

## Handoff Format

Each implementation handoff under `docs/` must record:

- Completed work
- Pending work
- Validation performed
- Next implementation item
