# Story 004: Agent generation and audit trace

## Status

Complete

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
- Added session-aware generation: a valid existing session contributes its latest ten tenant/user-scoped messages to the OpenRouter chat context before the current grounded prompt.
- Changed supplied unknown or unauthorized session IDs from silent new-session creation to a not-found response, preventing lost or substituted conversational context.
- Replaced the narrow inline intent heuristic with a deterministic `IntentClassifier` covering common comparison, report, and summary synonyms while preserving explicit precedence and `LOOKUP` fallback.
- Added focused regression coverage for the history window, strict session rejection, and classifier phrase/precedence behavior.
- Added comprehensive failure-path and resilience tests for `OpenRouterGenerationService` covering blank API key, HTTP 4xx (429/401) error fallback, HTTP 5xx (500/503) error fallback, malformed JSON and code fence parsing in comparison responses, and circuit breaker trip to OPEN state.
- Added `LiveProviderIntegrationTest` executing live OpenRouter generation and comparison when `OPENROUTER_API_KEY` is present.

## Pending Work

- Validate the containerized Testcontainers workflow once the remote Podman API socket is available.
- Add operational circuit-breaker metrics if required by deployment consumers.

## Validation Performed

- `./gradlew test --tests com.findoc.service.agent.AgentServiceTest --console=plain` passed after the agent-generation updates.
- The focused generation/session regression now confirms the new flow operates as expected in unit test conditions.
- The focused agent-service suite passed after adding structured citations, trace/session lookup methods, and document comparison.
- `./gradlew test --tests com.findoc.service.agent.AgentServiceTest --console=plain` and `./gradlew test --tests com.findoc.service.agent.IntentClassifierTest --console=plain` passed after session-context and classifier updates.
- `./gradlew test --tests com.findoc.service.agent.OpenRouterGenerationServiceTest --console=plain` passed all failure path and fallback scenarios.
- `./gradlew test --console=plain` passed after the combined agent, persistence, and ingestion updates.
- `./gradlew localIntegrationTest --console=plain` passed against local PostgreSQL/pgvector and Kafka.

## Next Implementation Item

Monitor provider latency and circuit-breaker behavior in production deployments.
