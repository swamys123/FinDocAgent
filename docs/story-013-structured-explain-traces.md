# Story 013: Structured Explain Traces

## Status

Complete

## Completed Work

- Changed `AgentTraceResponse.fullTrace` from stage-name strings to structured step objects.
- Added per-stage `step`, `tool`, bounded `input` and `output`, and `durationMs` values for query traces.
- Persisted query and comparison traces as JSON in the `query_traces.steps` JSONB column.
- Added legacy conversion and read fallback for historical pipe-delimited traces.
- Preserved tenant and authenticated-user filtering for trace lookup.
- Updated controller and service coverage for nested response fields and persisted trace JSON.
- Updated the specification and developer API examples.

## Pending Work

- Validate the new Liquibase migration against the live PostgreSQL stack with existing trace rows.
- Verify generated OpenAPI and runtime `/api/v1/agent/explain/{queryId}` output after deployment.

## Validation Performed

- `./gradlew test --tests com.findoc.controller.AgentControllerTest --tests com.findoc.service.agent.AgentServiceTest --console=plain` passed.

## Next Implementation Item

Run the full unit suite and the local PostgreSQL/Kafka integration suite, then verify the migration against persisted legacy traces.