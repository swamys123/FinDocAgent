# FinDoc Agent: Backend and Developer Setup

Tenant-aware document intelligence application built with Spring Boot 3.2, Java 17, PostgreSQL with pgvector, Apache Kafka, and a Vite/React frontend. It supports document upload and download, asynchronous PDF/text ingestion, cosine-similarity retrieval, grounded agent responses with citations, session-aware queries, document comparison, query traces, and tenant-scoped persistence.

## Prerequisites

For host-based local runs:
- Java 17
- PostgreSQL with pgvector on `localhost:5432`
- Apache Kafka on `localhost:9092`
- Node.js and npm (current LTS) for the frontend
- PostgreSQL test database `findoc-test-db` for `localIntegrationTest`
- Docker Engine with an accessible socket for the containerized `integrationTest` and `correctnessTest`

## Run with Docker Compose

See the root [Compose quick start](../README.md#quick-start-run-everything-with-docker-compose) for the full instructions, ports, and shutdown commands. It starts PostgreSQL/pgvector, Kafka, the backend, and the frontend using `findoc-agent/.env`.

## Run Locally (Backend)

From this directory:

```bash
cp .env.example .env
```

Set a unique `JWT_SECRET` with at least 32 characters. Provider keys are required for live Gemini embedding and OpenRouter generation validation, but are optional for application startup. `.env` is local and Git-ignored; use environment injection or a secret manager in CI and deployments.

Start the backend:

```bash
./gradlew bootRun --console=plain
```

It listens on `http://localhost:8080`. The health check is:

```bash
curl -i http://localhost:8080/actuator/health
```

The public routes are `/actuator/health`, `/api/v1/auth/token`, `/v3/api-docs/**`, and `/swagger-ui/**`. All other requests require bearer-token authentication. The seeded local demo account is tenant `00000000-0000-0000-0000-000000000001`, username `demo@findoc.local`, and password `demo123`.

## API Docs (Swagger UI)

When the backend is running, the generated OpenAPI specification is available at:

- JSON specification: http://localhost:8080/v3/api-docs
- Swagger UI: http://localhost:8080/swagger-ui/index.html

Swagger UI documents the authentication, document, and agent APIs. Use its **Authorize** control with the JWT returned by `/api/v1/auth/token` to call protected operations.

## Frontend

The basic React frontend provides login, document upload/list/status polling/delete, document selection, and session-aware agent queries. Session history, document comparison, and query explanation remain backend API workflows.

From `frontend/`:

```bash
cp .env.example .env
npm ci
npm run dev
```

The development server listens on `http://localhost:5173`. Set `VITE_API_BASE_URL` in `frontend/.env` when the backend uses another address. The backend allows this origin by default; configure `FINDOC_CORS_ALLOWED_ORIGINS` for other origins.

## Configuration and Limits

Use `.env.example` as the non-secret configuration reference. It includes database/Kafka settings, the local integration test database name, Gemini and OpenRouter provider settings, ingestion topic and retry settings, and rolling log configuration. Additional settings such as provider circuit-breaker thresholds and CORS origins can be supplied as environment variables; they are configured in `application.yml` but are not listed in `.env.example`. Gradle loads the populated, Git-ignored `.env` into backend run and test processes without modifying `application.yml`.

- Uploads are limited to 20 MB.
- Ingestion chunks contain up to 512 whitespace-delimited terms with a 50-term overlap; this is not model-tokenizer-based chunking.
- Retrieval returns five sources by default.
- The query path is a fixed classify, retrieve, and generate sequence, not an iterative agent loop. `agent.max-iterations` defaults to five and is clamped to at most five, but does not drive the current query flow.
- Ingestion retries use exponential backoff and route exhausted messages to `findoc.ingestion.dlq`.
- OpenRouter generation and comparison have deterministic fallback behavior; Gemini embedding requires a working provider.

## Run the Tests

Run from this directory:

```bash
./gradlew compileJava --console=plain
./gradlew test --console=plain
./gradlew localIntegrationTest --console=plain
./gradlew integrationTest --console=plain
./gradlew correctnessTest --console=plain
```

Run frontend checks from `frontend/`:

```bash
npm run build
npm run lint
```

`test` runs the backend unit suite without Docker or live services. `integrationTest` runs only tests tagged `integration`; these use Testcontainers for PostgreSQL/pgvector and Kafka and require an accessible Docker socket. This task is separate from `localIntegrationTest`, which runs tests tagged `local-integration` against the PostgreSQL database named by `TEST_DB_NAME` (default `findoc-test-db`) and the Kafka broker configured by `KAFKA_BOOTSTRAP_SERVERS`. The local database must already exist before running the local suite:

```sql
CREATE DATABASE "findoc-test-db";
```

The local suite validates Liquibase, BYTEA source persistence, pgvector cosine retrieval, tenant isolation, soft-deleted chunks, Kafka ingestion, chunking, and document readiness with deterministic test embeddings. Live Gemini and OpenRouter checks are also tagged `local-integration`; after the Spring context starts, they are skipped when their provider API keys are absent. The local settings are in `src/test/resources/application-local-pg.yml` and can be overridden by environment variables. Detailed endpoint examples and current runtime notes are in the [developer guide](../docs/dev-guide/README.md).

`correctnessTest` runs the distributed-systems tests tagged `correctness`, also using Testcontainers for PostgreSQL/pgvector and Kafka. It exercises upload/outbox atomicity and relay recovery, ingestion redelivery and concurrency, poison-message handling, dead-letter publishing and replay contracts, and upload idempotency/content deduplication. Audits 1–4 are implemented and covered by this suite. If Testcontainers reports that Docker API 1.32 is too old, run either Docker-backed task with `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`:

```bash
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew correctnessTest --console=plain
```
