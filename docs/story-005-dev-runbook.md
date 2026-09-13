# Story 005: Developer runbook and endpoint validation

## Status

Complete

## Completed Work

- Added a dedicated developer usage guide under `docs/dev-guide/` covering local setup, startup, and endpoint validation.
- Documented the environment variables, provider circuit breakers, Kafka retry/DLQ settings, upload limits, seeded demo credentials, and project expectations for running the app locally.
- Included sample curl examples for auth, document upload/status, and agent querying.
- Documented asynchronous upload processing, document readiness states, tenant-scoped errors, provider fallback behavior, and live integration-validation boundaries.

## Pending Work

- Live PostgreSQL/pgvector and Kafka workflow validation remains pending, as does credential-backed Gemini/OpenRouter validation and controller contract coverage.

## Validation Performed

- `cd findoc-agent && ./gradlew test --console=plain` — passed.
- `cd findoc-agent && ./gradlew bootRun --console=plain` — application started successfully and reached the embedded Tomcat startup phase on port 8080.
- Documentation was checked against `application.yml`, `.env.example`, document/agent controllers, ingestion services, provider services, and the global exception handler.

## Next Implementation Item

Complete live Kafka/pgvector validation, provider resilience checks, and controller/API contract coverage, then update this guide with the observed results.
