# FinDoc Agent

Tenant-aware document intelligence application built with Spring Boot 3.2, Java 17, PostgreSQL with pgvector, Apache Kafka, and a Vite/React frontend. It supports document upload and download, asynchronous PDF/text ingestion, cosine-similarity retrieval, grounded agent responses with citations, session-aware queries, document comparison, query traces, and tenant-scoped persistence.

## Requirements

- Java 17
- PostgreSQL with pgvector on `localhost:5432`
- Apache Kafka on `localhost:9092`
- Node.js and npm (current LTS) for the frontend
- Podman with an accessible socket for `integrationTest`

## Local Run

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

The public endpoints are `/actuator/health` and `/api/v1/auth/token`; all other API routes require a bearer token. The seeded local demo account is tenant `00000000-0000-0000-0000-000000000001`, username `demo@findoc.local`, and password `demo123`.

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

Use `.env.example` as the non-secret configuration reference. It includes database/Kafka settings, Gemini and OpenRouter provider settings, ingestion topic and retry settings, provider circuit-breaker settings, CORS, and rolling log configuration.

- Uploads are limited to 20 MB.
- Ingestion chunks use 512 tokens with a 50-token overlap.
- Retrieval returns five sources by default.
- The agent loop is capped at five iterations.
- Ingestion retries use exponential backoff and route exhausted messages to `findoc.ingestion.dlq`.
- OpenRouter generation and comparison have deterministic fallback behavior; Gemini embedding requires a working provider.

## Validation

Run from this directory:

```bash
./gradlew compileJava --console=plain
./gradlew test --console=plain
./gradlew integrationTest --console=plain
```

Run frontend checks from `frontend/`:

```bash
npm run build
npm run lint
```

`integrationTest` uses Testcontainers and requires an accessible Podman socket. Unit tests and startup do not replace live PostgreSQL/pgvector, Kafka, Gemini, or OpenRouter validation. Detailed endpoint examples and current runtime notes are in the [developer guide](../docs/dev-guide/README.md).
