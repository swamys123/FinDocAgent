# Story 009: Public documentation alignment

## Status

Complete

## Completed Work

- Replaced public real-company target-persona examples with generic organization categories.
- Replaced the LICENSE copyright holder with the user-authorized `ABC Company` attribution.
- Labeled local demo identifiers as fictional and retained them only as local-development fixtures.
- Aligned specification API examples with the delivered records and controllers, including `tenantId` authentication, document response fields, download support, `204 No Content` deletion, source-array comparison responses, and stage-level query traces.
- Distinguished delivered retrieval/generation behavior from future dynamic LLM tool selection, structured tool-event traces, Swagger UI, and one-command compose startup.
- Updated root and module README material with the download endpoint, servlet multipart enforcement, local infrastructure requirements, and Testcontainers/Podman caveat.
- Added refreshed screenshots for the sample-agreement SLA comparison and cross-section penalty follow-up, replacing the previous query screenshots in the README.
- Replaced the recent-query listing and execution-trace screenshots in the README with the latest captures.

## Pending Work

- Run live PostgreSQL/pgvector and Kafka workflow validation with a populated local environment.
- Validate Gemini and OpenRouter with injected credentials and complete provider failure-path coverage.
- Add controller/API contract tests and OpenAPI documentation if required by deployment consumers.

## Validation Performed

- Reviewed the README prompts against the sample agreement's Sections 3, 4.1–4.3, 5.1–5.2, and 8.3–8.4.
- Confirmed the README links to both newly attached screenshots in `screens/` and no longer displays the previous query screenshots.
- Verified the updated execution-detail screenshot matches the attached capture.
- Documentation-only update; no build or test run required.
- Searched public Markdown and LICENSE content for the removed real-company and personal identifiers; no matches remained.
- Checked documentation contracts against `AuthRequest`, `DocumentController`, `DocumentResponse`, `AgentController`, `AgentResponse`, `DocumentComparisonResponse`, `AgentTraceResponse`, `application.yml`, and `build.gradle`.
- Ran `cd findoc-agent && ./gradlew test --console=plain` successfully.

## Next Implementation Item

Continue live PostgreSQL/pgvector and Kafka workflow validation.