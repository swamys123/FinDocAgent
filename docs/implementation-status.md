# Implementation Status

This file is the authoritative starting point for implementation work across sessions.

## Current Phase
- Status: Upload, ingestion, vector retrieval, agent query, comparison, trace, provider resilience, and frontend workflows are implemented. PDF page counts are persisted, but chunk-level page numbers are not currently populated. Audits 1–4 are implemented, including the transactional ingestion outbox, idempotent ingestion, DLQ publication/recovery and observability, content-hash deduplication, tenant-scoped HTTP idempotency, and a manual frontend upload retry that reuses its idempotency key.
- Completed: Gradle Spring Boot project, tenant-aware JWT authentication, protected document APIs, tenant-scoped persistence, durable BYTEA upload sources, transactional ingestion outbox and `SKIP LOCKED` Kafka relay, partial unique indexes for live document chunks and tenant-scoped document content hashes, SHA-256 backfill and upload deduplication, failed-duplicate requeue, conflict-upserting chunk writes, atomic tenant-scoped ingestion claim/completion/failure transitions and READY no-op handling, embedding calls outside transactions with durable per-chunk staging and retry reuse, stale claim heartbeat/recovery through the outbox, explicit record-level Kafka acknowledgments and poison-message deserialization recovery, retry-count DLQ metadata, publish-before-fail recovery ordering, configured DLQ topic partitions/replication/retention, DLQ published-job metric and Actuator metrics exposure, tenant-scoped failure reasons in document status responses, tenant-admin replay, manual frontend upload retry with stable per-attempt idempotency keys, plus the existing agent, security, persistence, API, and provider features described in prior stories.
- Pending: Production Kafka topic creation permissions/effective replication and retention settings and deployment alert configuration require environment verification. Frontend component-level tests are not configured; repository-wide frontend build/lint are blocked by existing errors in `LoginPage.tsx` and `TracesPage.tsx` (the changed frontend modules pass scoped lint/type-check). Operational circuit-breaker metrics remain optional if required by deployment consumers.
- Validation history: `test` passed all 97 backend unit tests and `correctnessTest` passed all 38 Testcontainers correctness tests against Docker Engine 29 with `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`. The Docker-backed `integrationTest` is configured separately from local integration tests; see Story 023 for current verification. `localIntegrationTest` requires the configured local PostgreSQL test database to exist before execution. Modified frontend files passed focused ESLint and TypeScript checks. The full frontend `npm run build`/`npm run lint` remain blocked by the unrelated pre-existing issues noted above. Live provider tests still require credentials and are skipped when API keys are absent.
- Next item: Verify DLQ topic settings and alerts in the target deployment, and add a frontend component-test runner when frontend test coverage is prioritized.
- Story 016: The source-mounted full-stack Compose quickstart is implemented and runtime-verified with Docker Compose; all four services are running, the backend health check and demo auth return HTTP 200, and the Kafka ingestion consumer is registered. Gemini-backed document ingestion and volume persistence across restart remain unverified.

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

| Story 013 | [Structured explain traces](story-013-structured-explain-traces.md) | Complete | Validate the new Liquibase migration against the live PostgreSQL stack with existing trace rows |

| Story 014 | [Recent traces listing UI](story-014-recent-traces-listing.md) | Complete | Run the full unit suite and manually validate `/traces` against the live PostgreSQL/Kafka stack |
| Story 015 | [README operational source of truth](story-015-readme-operational-source-of-truth.md) | Complete | Keep the runbook aligned with runtime and validation changes |

| Story 016 | [Full-stack Compose quickstart](story-016-compose-quickstart.md) | Complete | Verify Gemini-backed ingestion and volume persistence across restart |

| Story 017 | [Transactional ingestion outbox](story-017-transactional-outbox.md) | Complete | Implement Audit 2 ingestion idempotency |

| Story 018 | [Audit 2 ingestion idempotency](story-018-audit-2-ingestion-idempotency.md) · [Design decisions](story-018-audit-2-ingestion-idempotency-design-decisions.md) | Complete | Verify production Kafka topic configuration and alerting |

| Story 019 | [Audit 4 tenant-scoped idempotency keys](story-019-idempotency-keys.md) | Complete | Verify production Kafka topic configuration and alerting |

| Story 020 | [Tenant-admin DLQ replay](story-020-dlq-replay.md) · [Design decisions](story-020-dlq-replay-design-decisions.md) | Complete; correctness integration tests pass in Docker-enabled environment | Verify production DLQ configuration |

| Story 021 | [README factual consistency review](story-021-readme-fact-check.md) | Complete | Keep public documentation aligned with implementation changes |

| Story 022 | [Distributed-systems audit completion](story-022-audit-completion.md) | Complete; all 38 correctness tests pass | Verify production Kafka topic configuration and alerting |

| Story 023 | [Test suite readiness and scope](story-023-test-suite-readiness.md) | Complete | Provision the local integration test database before running `localIntegrationTest`; production Kafka verification remains pending |

| Audit | [Distributed-systems correctness audit (1–4) and correctness tests](audit/correctness-audit.md) | Implemented; 38/38 correctness tests pass | Verify production-only configuration and add frontend component tests when tooling is selected |

## Priority Order

1. Verify configured DLQ replication, retention, permissions, and alerting in the target deployment.
2. Add frontend component tests once a project-wide test runner is selected.
3. Add operational circuit-breaker metrics only if required by deployment consumers.

## Handoff Format

Each implementation handoff under `docs/` must record:

- Completed work
- Pending work
- Validation performed
- Next implementation item
