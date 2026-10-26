# Story 023 — Test suite readiness and scope

## Completed work

- Restricted `integrationTest` to JUnit tests tagged `integration`, keeping local database/provider checks in `localIntegrationTest`.
- Configured the Docker-backed PostgreSQL/Kafka integration test to use the repository's XML Liquibase master changelog and PostgreSQL Hibernate dialect rather than the H2 test default.
- Updated backend test instructions to distinguish unit, Docker-backed integration, local integration, and correctness suites and their prerequisites.
- Corrected outdated README and developer-guide claims that DLQ audit work was unfinished.
- Recorded that the local integration profile targets a pre-existing PostgreSQL test database. The profile was updated separately and preserved.
- Confirmed the frontend has no configured component test runner and reproduced the documented full frontend build/lint errors. Frontend test tooling and unrelated page errors remain outside this story.

## Pending work

- Create the PostgreSQL database configured by `TEST_DB_NAME` before running `localIntegrationTest`; provide reachable local Kafka.
- Supply provider credentials if live Gemini/OpenRouter integration validation is required. Those tests are skipped without keys once their Spring context can start.
- Verify effective Kafka DLQ topic settings, production permissions, and deployment alerting.
- Address the existing frontend build/lint errors and select a component test runner in a separate task.

## Validation performed

- In an environment-file-free checkout, `test`: **97 passed**, `integrationTest`: **1 passed**, and `correctnessTest`: **38 passed**, using `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` for Docker API compatibility.
- Before the fix, `integrationTest` selected six tests, including local-database/provider tests. Tag filtering isolated its intended Testcontainers class; running it then exposed both the wrong Liquibase changelog and the H2 dialect inherited from test resources. The explicit XML changelog and PostgreSQL dialect resolved the context startup errors.
- `localIntegrationTest` was retried with the user's updated local profile; all five tests remain blocked during application startup because the configured local test database does not exist.
- In an environment-file-free frontend checkout, `npm run build` fails on the unused `setTenantId` in `LoginPage.tsx`; `npm run lint` reports that issue plus the synchronous effect state update in `TracesPage.tsx`.

## Next implementation item

Provision the local integration test database before running `localIntegrationTest`; continue production Kafka configuration and alert verification as tracked in the implementation status.
