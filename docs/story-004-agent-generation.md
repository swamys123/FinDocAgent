# Story 004: Agent generation and audit trace

## Status

In progress

## Completed Work

- Added the session entity model and persistence layer for agent sessions, session messages, and query traces.
- Added `OpenRouterGenerationService` with a local fallback so the agent returns a useful answer even when no API key is configured.
- Updated `AgentService` to create or reuse a tenant-scoped session, persist both user and assistant messages, and save a query trace.
- Added a focused regression test for the agent generation flow and session persistence.
- Added `openrouter.*` configuration values to application settings.
- Returned structured query sources containing chunk/document IDs, filename, content, cosine similarity score, and page number.
- Returned the persisted query-trace ID from agent queries and recorded elapsed query duration.
- Added tenant- and user-scoped session history and trace-explanation endpoints.
- Added tenant-safe document comparison: both documents must be distinct, tenant-owned, and `READY`; relevant chunks are retrieved independently for each document.
- Added structured comparison generation using OpenRouter JSON responses, with a deterministic local fallback that provides a summary, similarities, and differences.
- Extended focused agent-service coverage for source citations, persisted query IDs, independent comparison retrieval, and structured comparison output.

## Pending Work

- Validate the actual OpenRouter response contract and failure handling under real credentials.
- Add controller/API-contract coverage for query, session-history, trace-explanation, and comparison endpoints.
- Complete provider circuit-breaker failure-path tests and validate Gemini/OpenRouter behavior with real provider responses.
- Add controller/API-contract coverage for query, comparison, session history, trace explanation, upload, download, status, list, and delete endpoints.
- Run PostgreSQL/pgvector and Kafka workflow validation against a real stack.
- Add OpenAPI documentation and operational circuit-breaker metrics if required by deployment consumers.

## Validation Performed

- `./gradlew test --tests com.findoc.service.agent.AgentServiceTest --console=plain` passed after the agent-generation updates.
- The focused generation/session regression now confirms the new flow operates as expected in unit test conditions.
- The focused agent-service suite passed after adding structured citations, trace/session lookup methods, and document comparison.
- Provider resilience compilation, focused circuit-breaker/Gemini tests, and the full `./gradlew clean test --console=plain` suite pass. OpenRouter provider failure-path tests and full workflow validation remain pending.
- The integration task is currently blocked before execution because Testcontainers cannot find a Docker-compatible client while the Podman remote socket is unavailable.

## Next Implementation Item

Run full unit validation and PostgreSQL/pgvector/Kafka workflow validation, then add API contract and provider failure-path coverage.
