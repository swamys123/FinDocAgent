# Story 011: Controller Contracts and OpenAPI

## Status

Complete

## Completed Work

- Added Springdoc WebMVC UI for generated OpenAPI 3 documentation and Swagger UI.
- Added `OpenApiConfig` with FinDoc API metadata, local server information, and bearer JWT security scheme metadata.
- Documented all implemented authentication, document, and agent controller operations with summaries, request/response schemas, validation responses, path parameters, security requirements, and actual `202` upload and `204` delete statuses.
- Added focused web-layer tests for authentication, document upload/list/status/download/delete, agent query/compare/session/explain, request validation, response shapes, service delegation, and protected-route access.
- Added OpenAPI configuration coverage for API metadata and bearer authentication.

## Pending Work

- Verify `/v3/api-docs` and Swagger UI against the running application.
- Complete live PostgreSQL/pgvector, Kafka, Gemini, and OpenRouter validation.
- Add broader controller contract coverage if deployment consumers require detailed RFC 7807 response schemas or OpenAPI examples.

## Validation Performed

- `./gradlew compileJava --console=plain` passed.
- Focused controller and OpenAPI tests passed:
  `./gradlew test --tests 'com.findoc.controller.*' --tests 'com.findoc.config.OpenApiDocumentationTest' --console=plain`.
- Full unit suite passed with `./gradlew test --console=plain`.

## Next Implementation Item

Run `./gradlew test --console=plain`, then execute live PostgreSQL/pgvector and Kafka workflow validation and complete provider failure-path coverage.
