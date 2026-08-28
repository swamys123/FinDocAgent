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
- Added tenant-isolation and soft-delete coverage for document repository queries.
- Added validated document lifecycle transitions and a retry count increment on failure.
- Added deterministic newest-first ordering and a 100-document bound to document listing.
- Restored Lombok compile-time configuration required by the entity mappings.
- Made authentication tenant-aware by requiring `tenantId` and removing the unscoped user lookup.
- Added the asynchronous ingestion handoff: uploads persist source files and publish tenant-scoped Kafka messages while the worker extracts PDF/text, chunks content, and advances document status.
- Added bounded retry and DLQ routing for ingestion messages.

## Current Limitations

- The document workflow is still a minimal persistence implementation; status transitions are not yet fully asynchronous.
- Gemini embeddings are not yet connected; ingestion currently persists chunks without vectors.
- The database schema is a baseline for tenant isolation and document persistence, not yet the full event-driven ingestion model.
- Existing clients using only username and password must include the tenant UUID in token requests.
- The remaining Stories 003 and 004 still need the Kafka job, vector retrieval, model integrations, and audit/session persistence.

## Validation Performed

- Ran `./gradlew clean test --console=plain` in `findoc-agent` after the asynchronous ingestion changes.
- Result: `BUILD SUCCESSFUL` and exit code 0.

## Next Implementation Item

Add Gemini embeddings and pgvector persistence to the ingestion pipeline, with tenant-scoped vector retrieval.
