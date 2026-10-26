# Story 023 — Test suite readiness and scope

## Completed work

- Restricted `integrationTest` to JUnit tests tagged `integration`, keeping local database/provider checks in `localIntegrationTest`.
- Configured the Docker-backed PostgreSQL/Kafka integration test to use the repository's XML Liquibase master changelog and PostgreSQL Hibernate dialect rather than the H2 test default.
- Updated backend test instructions to distinguish unit, Docker-backed integration, local integration, and correctness suites and their prerequisites.
- Corrected outdated README and developer-guide claims that DLQ audit work was unfinished.
- Recorded that the local integration profile targets a pre-existing PostgreSQL test database. The profile was updated separately and preserved.
- At the time of this story, the frontend had no configured component test runner and full frontend build/lint errors were reproduced. Frontend test tooling and page errors were resolved later in Story 025.
- The frontend build/lint blockers and test-runner gap were subsequently addressed in [Story 025](story-025-frontend-readiness.md).

## Pending work

- Verify effective Kafka DLQ topic settings, production permissions, and deployment alerting.

## Validation performed

- In an environment-file-free checkout, `test`: **97 passed**, `integrationTest`: **1 passed**, and `correctnessTest`: **38 passed**, using `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` for Docker API compatibility.
- Before the fix, `integrationTest` selected six tests, including local-database/provider tests. Tag filtering isolated its intended Testcontainers class; running it then exposed both the wrong Liquibase changelog and the H2 dialect inherited from test resources. The explicit XML changelog and PostgreSQL dialect resolved the context startup errors.
- On 2026-10-26, verified PostgreSQL was accepting connections and ran `./gradlew localIntegrationTest --console=plain` from `findoc-agent/`: **5 passed, 0 failed** (3 live provider, 1 Kafka ingestion, 1 PostgreSQL/pgvector). The configured database and local services are available, so database provisioning is no longer pending.
- Historical baseline reproduction before Story 025: in an environment-file-free frontend checkout, `npm run build` failed on the unused `setTenantId` in `LoginPage.tsx`; `npm run lint` reported that issue plus the synchronous effect state update in `TracesPage.tsx`.

## Next implementation item

Verify production Kafka DLQ configuration, permissions, and alerting as tracked in the implementation status.
