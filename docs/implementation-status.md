# Implementation Status

This file is the authoritative starting point for implementation work across sessions.

## Current Phase
- Status: Upload, ingestion, chunking, embedding, vector retrieval, structured agent query sources, session-aware generation, explain traces, document comparison, provider resilience, failure-path coverage, live Gemini/OpenRouter validation, local dotenv loading, public-documentation alignment, root-project onboarding documentation, and a basic React frontend (login, upload, document status polling, query) are implemented and validated.
- Completed: Gradle Spring Boot project, tenant-aware JWT authentication, protected document APIs, tenant-scoped persistence, durable BYTEA upload sources, confirmed Kafka publication, a single Spring Kafka ingestion/retry/DLQ path with configurable exponential backoff, PDF/text extraction with page-count persistence, chunking, soft-deleted document chunks with active-only cosine retrieval, vector schema, Gemini response validation, the Gemini model-contract fix for the unsupported default model, tenant-safe cosine retrieval, bounded agent query flow, tenant/user-scoped ten-message session context passed into generation with strict session lookup, deterministic four-intent classification, session/trace persistence, structured query citations, OpenRouter-backed generation with structured comparison fallback, tenant-safe document comparison, the PostgreSQL BYTEA/OID schema mismatch fix for document sources, request-scoped MDC tracing for trace_id/tenant_id/user_id, original-file retrieval support via the document download endpoint, daily log rollover with dated archive naming and no deletion of historical logs, the pgvector Hibernate mapping fix for chunk embedding persistence (`float[]` with `SqlTypes.VECTOR` + `hibernate-vector`), local dotenv configuration with a non-secret template and Copilot secret-handling safeguards, the shared provider circuit-breaker implementation with configurable thresholds and OpenRouter fallback behavior, comprehensive failure-path tests for Gemini (4xx, 5xx, malformed payloads, circuit-open) and OpenRouter (4xx, 5xx, timeout, circuit-open, comparison fallback), live provider integration tests for Gemini embedding and OpenRouter generation/comparison, aligned root/developer documentation for the React frontend, CORS, and current API examples, controller contract tests for all implemented APIs, and generated OpenAPI/Swagger configuration with bearer JWT metadata.
- Pending: Containerized Testcontainers workflow verification once the remote Podman API socket is available; operational circuit-breaker metrics if required by deployment consumers.
- Validation: focused controller and OpenAPI tests pass, the full `./gradlew test --console=plain` suite passes, and `./gradlew localIntegrationTest --console=plain` passes against local PostgreSQL/pgvector and Kafka using the hidden `.env` environment. The local suite validates Liquibase migrations, native `<=>` retrieval, tenant isolation, chunk soft deletion, BYTEA-backed ingestion, Kafka JSON publication/consumption, chunking, document readiness, and live Gemini/OpenRouter provider interactions.
- Next item: Operational metrics and end-to-end multi-tenant load validation.

## Stories and Phases

| ID | Handoff | Status | Next item |
| --- | --- | --- | --- |
| Setup | This file | Complete | Register the first implementation story |

| Standards | Repository guidance | Complete | Apply standards to the first implementation story |

| Story 001 | [Gradle foundation and core API slice](story-001-gradle-foundation.md) | Complete | Implement PostgreSQL entities and tenant-scoped repositories |

| Story 002 | [Persistence and tenant isolation](story-002-persistence.md) | Complete | Add Gemini embeddings and pgvector retrieval |

| Story 003 | [Embeddings and vector retrieval](story-003-embeddings.md) | Complete | Complete provider resilience tests and live provider validation |

| Story 004 | [Agent generation and audit trace](story-004-agent-generation.md) | Complete | Add operational circuit-breaker metrics if required |

| Story 005 | [Developer runbook and endpoint validation](story-005-dev-runbook.md) | Complete | Keep the runbook aligned with evolving API and infrastructure changes |

| Story 006 | [File-based troubleshooting logging](story-006-file-logging.md) | Complete | Keep the log location aligned with the runtime environment |

| Story 007 | [Local dotenv configuration](story-007-local-dotenv-configuration.md) | Complete | Validate a populated `.env` against the live PostgreSQL/Kafka stack |

| Story 008 | [Root README onboarding](story-008-root-readme-onboarding.md) | Complete | Keep setup and feature documentation aligned with implementation changes |

| Story 009 | [Public documentation alignment](story-009-public-documentation-alignment.md) | Complete | Complete live infrastructure and provider validation |

| Story 010 | [Basic React frontend](story-010-basic-react-frontend.md) | Complete | Keep the frontend aligned with backend API changes |

| Story 011 | [Controller contracts and OpenAPI](story-011-controller-contracts-openapi.md) | Complete | Run live infrastructure/provider validation and verify generated docs at runtime |

| Story 012 | [Architecture and portfolio positioning](story-012-architecture-and-portfolio-positioning.md) | Complete | Keep the architecture diagram aligned with runtime changes |

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
