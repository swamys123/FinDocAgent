# Story 026 — Metrics, tracing, and local observability

## Completed work

- Added Micrometer Prometheus metrics for ingestion consumer lag, pending outbox count and age, DLQ depth, provider circuit-breaker state, provider request latency, provider-reported token usage, and estimated token cost.
- Added opt-in OpenTelemetry OTLP export to Tempo and W3C trace-context propagation through the transactional outbox, Kafka producer, consumer retries, and DLQ publication.
- Added an opt-in Docker Compose observability profile with Prometheus, Grafana, and Tempo, including provisioned data sources and a dashboard.
- Enabled Prometheus histogram buckets for HTTP server requests and added a Grafana p95 latency panel for the agent query and explain APIs, grouped by URI.
- Added `docker-compose.observability.yml` so opting into the Compose observability stack also activates Spring's `observability` profile; the base Compose startup remains unchanged.
- Updated the root README and backend developer runbook with startup/shutdown commands, service URLs, credentials, cost-rate configuration, and screenshots of the dashboard, agent API/provider p95 latency, and an API trace; aligned the developer guide with these instructions and checked its cross-links.
- Added focused tests for trace-context propagation and provider telemetry.

## Pending work

- Verify production Kafka topic permissions, replication, retention, alert configuration, and telemetry retention/rate settings in the target deployment.
- Provider token counts and estimated costs remain unavailable until providers return usage metadata and the relevant USD-per-million-token rates are configured.

## Validation performed

- Focused provider telemetry, circuit-breaker, trace-context, and MDC tests passed.
- The full backend unit suite and Spring application-context test passed.
- Live Compose validation confirmed backend health, a successful Prometheus scrape, provisioned Grafana dashboard panels, and an ingestion trace spanning HTTP request, outbox publication, Kafka send/receive retries, and DLQ publication.
- Captured and reviewed `screens/observability-overview.png` and `screens/observability-trace.png`.
- Added `screens/api_p95_latency.png`, `screens/provider_p95_latency.png`, and `screens/api_trace.png` as README examples of the dashboard's latency and trace views.
- The full backend `test` and `correctnessTest` Gradle suites passed after explicitly enabling observations in the custom Kafka listener factory.
- Validated the base Compose configuration and the merged observability override; the base leaves the Spring profile unset, while the override sets it to `observability`.
- Reproduced the scrape failure with the base profile, then applied the override and verified Prometheus reports `up{job="findoc-agent"} = 1`.
- Validated the dashboard JSON and confirmed Prometheus accepts the p95 query. No matching bucket series are present until the backend is restarted with the histogram configuration and agent API requests are generated.
- Reviewed all repository README files for observability and setup consistency; the frontend README required no changes.
- The backend Gradle test task was not run because the build script loads the local `.env` file.

## Next implementation item

Verify production Kafka and telemetry configuration and alerting; see [implementation-status.md](./implementation-status.md) for the current priority order.
