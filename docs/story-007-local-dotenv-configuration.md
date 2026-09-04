# Story 007: Local dotenv configuration

## Status

Complete

## Completed Work

- Added optional `.env` loading to Gradle for `bootRun`, `test`, and `integrationTest`.
- Preserved OS and CI environment variables as higher-precedence values over local dotenv values.
- Added `.env.example` with every environment variable referenced by `src/main/resources/application.yml`.
- Kept real dotenv files Git-ignored and added a Copilot Chat search exclusion for dotenv paths.
- Added an instruction prohibiting agents from reading, searching, displaying, or modifying real dotenv files.
- Updated the developer guide to use the local dotenv workflow.

## Pending Work

- Populate a local `.env` with valid PostgreSQL, Kafka, and provider credentials, then complete live runtime validation.
- Configure CI and deployed environments through their secret manager or injected environment variables; they must not depend on `.env`.

## Validation Performed

- Ran `./gradlew test --tests com.findoc.service.agent.AgentServiceTest --console=plain` successfully after the Gradle task environment configuration was added.

## Next Implementation Item

Run `./gradlew bootRun --console=plain` with a populated local `.env` and confirm the health endpoint under the live PostgreSQL/Kafka stack.