# Story 002: Persistence and Tenant Isolation

## Status
Complete for current POC; runtime integration validation remains

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

## Current Limitations

- The document workflow is a minimal persistence implementation; production-grade delivery still requires an outbox or reconciliation strategy.
- Uploads persist durable BYTEA sources and hand ingestion to Kafka; confirmed publication failure handling is covered, but real Kafka validation remains.
- The database schema supports the current event-driven ingestion model, while native pgvector behavior still requires PostgreSQL validation.
- Existing clients using only username and password must include the tenant UUID in token requests.
- The remaining Stories 003 and 004 still need the Kafka job, vector retrieval, model integrations, and audit/session persistence.

## Validation Performed

- Ran `./gradlew test --tests com.findoc.entity.DocumentTest --tests com.findoc.repository.DocumentRepositoryTest --console=plain` in `findoc-agent`.
- Result: `BUILD SUCCESSFUL` and exit code 0.
- Full test and build validation is pending for this session.

## Next Implementation Item

Run PostgreSQL/pgvector and Kafka integration validation, then complete the agent response contract and generation boundary.
