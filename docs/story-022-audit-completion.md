# Story 022 — Distributed-systems audit completion

## Completed work

- Added persisted SHA-256 hashes for uploaded documents, backfilled hashes for existing document sources, and created a partial unique index for one active `(tenant_id, content_hash)` document.
- Serialized same-tenant/same-content uploads with a PostgreSQL transaction advisory lock, returned existing documents for active duplicates, allowed replacement after soft deletion, and re-enqueued failed duplicates.
- Added duplicate and failure-reason fields to document responses.
- Added DLQ retry-count headers, publish confirmation before marking a document failed, a successful-publish counter, and configurable DLQ topic partition/replication/retention settings.
- Added manual frontend retry that retains the selected file and reuses the same `Idempotency-Key` for that upload attempt.
- Corrected correctness test mock reset and context lifecycle to prevent embedding stubs/listeners for one test topic from affecting other classes. Strengthened success assertions so dedupe embedding/retrieval tests require READY documents and non-empty retrieval results.

## Pending work

- Verify DLQ topic creation permissions, effective replication/retention overrides, and alert configuration in the target production environment.
- Frontend has no component test runner. Repository-wide frontend build/lint remain blocked by unrelated existing errors in `LoginPage.tsx` and `TracesPage.tsx`; changed upload modules passed focused lint and type checks.
- Circuit-breaker metrics remain optional based on operational requirements.

## Validation performed

- `JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew correctnessTest`: **38 tests passed** with PostgreSQL/Kafka Testcontainers.
- `JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew test`: passed.
- Focused ESLint and TypeScript checks for `DocumentsPage.tsx`, `api/documents.ts`, and `types/api.ts`: passed.
- Full frontend `npm run build` and `npm run lint` attempted; both surface existing failures in `LoginPage.tsx` (`setTenantId` unused) and `TracesPage.tsx` (synchronous state update in effect).

## Next implementation item

Verify DLQ topic settings and alerting in the target deployment; consider adding a frontend component-test runner when that coverage is prioritized.
