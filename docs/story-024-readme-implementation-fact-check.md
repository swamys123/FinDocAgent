# Story 024: README implementation fact check

## Completed work

- Reviewed factual claims and operational instructions in the root README, the detailed developer guide, and the linked backend setup README against current Java sources, application configuration, Compose, tests, and frontend routes.
- Updated the backend setup README to describe chunk sizing as whitespace-delimited terms rather than model tokens, and to clarify that the configured maximum-iteration value does not drive the fixed query flow.
- Corrected its configuration reference to distinguish variables present in `.env.example` from optional environment variables supported by `application.yml`.
- Added Kafka to the detailed developer guide's local prerequisites and clarified that a previous local integration pass is historical; the latest recorded attempt was blocked because the configured PostgreSQL test database was absent.
- Preserved existing uncommitted documentation and implementation changes.

## Pending work

- No further README corrections were identified in the reviewed scope.
- Deployment-specific Kafka DLQ configuration and alerting remain pending as already recorded in the implementation status.

## Validation performed

- Compared documented endpoint paths with controller mappings; configuration values with `application.yml`, `.env.example`, and Compose; and query, chunking, retrieval, idempotency, and frontend claims with their implementation.
- Compared recorded local integration results with the latest test-suite handoff.
- Ran `git diff --check` after the documentation changes. Application tests were not rerun because this change only updates documentation.

## Next implementation item

Continue with the deployment verification and frontend test coverage priorities recorded in [implementation-status.md](./implementation-status.md).
