# Story 008: Root README onboarding

## Status

Complete

## Completed Work

- Replaced the minimal root README with a project overview and high-level feature inventory.
- Documented verified local requirements: Java 17, Gradle Wrapper 8.10.2, Spring Boot 3.2.12, PostgreSQL with pgvector, Kafka, and Podman for integration tests.
- Added local setup, health-check, configuration, common-command, and local-demo-data guidance.
- Linked to the existing API examples and developer guide instead of duplicating endpoint contracts.
- Documented environment-variable precedence and the requirement to keep secrets out of tracked dotenv files.

## Pending Work

- Keep the README aligned with future API, provider, deployment, and infrastructure changes.
- Complete live PostgreSQL/pgvector, Kafka, and provider validation when the local environment is available.

## Validation Performed

- Checked documented versions, commands, ports, and configuration-variable names against `findoc-agent/build.gradle`, `findoc-agent/gradle/wrapper/gradle-wrapper.properties`, `findoc-agent/.env.example`, and `findoc-agent/src/main/resources/application.yml`.
- Verified that the README links point to tracked developer documentation and API examples.

## Next Implementation Item

Run PostgreSQL/pgvector and Kafka workflow validation with a populated local environment, then add controller contract and provider failure-path coverage.