# Story 002: Persistence and Tenant Isolation

## Status
In progress

## Completed Work

- Added the JPA, PostgreSQL, Liquibase, and pgvector dependencies to the Gradle project.
- Added PostgreSQL datasource and JPA configuration in the application runtime config.
- Added H2-based test configuration so repository tests can run without a local Postgres instance.
- Added the first persistence entities for tenants, users, documents, and document chunks.
- Added the initial repository interfaces for tenant-aware lookup and document persistence.
- Added the initial Liquibase changelog with the database schema and demo tenant/customer seed data.
- Refactored authentication to resolve the user from persistence and validate the password hash with BCrypt.
- Refactored document storage away from the in-memory HashMap into repository-backed persistence.
- Added a repository-level test for the tenant-scoped user lookup contract.

## Current Limitations

- The document workflow is still a minimal persistence implementation; status transitions are not yet fully asynchronous.
- The upload flow still writes chunks directly in the same request path instead of handing work to Kafka.
- The database schema is a baseline for tenant isolation and document persistence, not yet the full event-driven ingestion model.
- The remaining Stories 003 and 004 still need the Kafka job, vector retrieval, model integrations, and audit/session persistence.

## Validation Performed

- Ran `./gradlew test --console=plain` in `findoc-agent`.
- Result: `BUILD SUCCESSFUL` and exit code 0.

## Next Implementation Item

Implement the full Postgres-backed document lifecycle and integration tests: verify status transitions, tenant-scoped document queries, and the document upload/list/delete flow against a real Postgres-backed environment before moving into Kafka ingestion.
