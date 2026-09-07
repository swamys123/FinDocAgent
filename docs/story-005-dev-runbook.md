# Story 005: Developer runbook and endpoint validation

## Status

Complete

## Completed Work

- Added a dedicated developer usage guide under `docs/dev-guide/` covering local setup, startup, and endpoint validation.
- Documented the exact environment variables, seeded demo credentials, and project expectations for running the app locally.
- Included sample curl examples for auth, document upload/status, and agent querying.
- Recorded the current sanity verification result from this environment.

## Pending Work

- None for this runbook task; the project is currently testable locally with the required PostgreSQL dependency in place.

## Validation Performed

- `cd findoc-agent && ./gradlew test --console=plain` — passed.
- `cd findoc-agent && ./gradlew bootRun --console=plain` — application started successfully and reached the embedded Tomcat startup phase on port 8080.

## Next Implementation Item

Keep the runbook current as the project evolves, especially when documenting live Kafka/pgvector validation, provider resilience settings, and API contract changes.
