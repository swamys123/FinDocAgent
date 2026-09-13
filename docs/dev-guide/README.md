# FinDoc Agent developer guide

## Current status

The implementation is now beyond the initial scaffold and includes the main tenant-aware backend flow for document ingestion and agent querying.

Verified in this workspace:

- `cd findoc-agent && ./gradlew test --console=plain` passed successfully.
- `cd findoc-agent && ./gradlew localIntegrationTest --console=plain` passed against local PostgreSQL/pgvector and Kafka.
- `cd findoc-agent && ./gradlew bootRun --console=plain` started the Spring Boot app and initialized Tomcat on port 8080.

Implemented so far:

- Gradle-based Spring Boot 3.2 Java 17 application scaffold
- Tenant-aware JWT authentication with both `tenant_id` and `user_id` claims
- Protected document APIs for listing, uploading, checking status, and deleting documents
- Tenant-scoped persistence and seeded demo tenant/user data
- Text/PDF extraction with page-count persistence and document source handling
- Chunking at 512 tokens with 50-token overlap
- pgvector-backed similarity search using cosine distance (`<=>`) with tenant and document scoping
- Kafka ingestion producer/consumer wiring with retry/DLQ handling checks
- Agent query flow with intent classification, vector retrieval, structured citations, and a five-iteration cap
- Tenant-scoped session history and query-trace explanation endpoints
- Tenant-safe document comparison with independent retrieval for each document
- Session and trace persistence with request-scoped MDC logging for `trace_id`, `tenant_id`, and `user_id`
- OpenRouter-backed generation with structured comparison fallback and response validation
- Basic React frontend with login, document management, status polling, document selection, and session-aware queries
- Backend CORS configuration for the local frontend origin, plus successful frontend build and lint validation
- Generated OpenAPI documentation and Swagger UI with bearer JWT security metadata

Current runtime caveat:

- The local PostgreSQL/pgvector and Kafka workflow is covered by `localIntegrationTest`; live Gemini and OpenRouter provider validation remains separate. The containerized `integrationTest` task still requires an accessible Podman socket.

## Prerequisites

- Java 17
- PostgreSQL running locally on `localhost:5432`
- Database name: `findoc`
- Database user: `postgres`
- Database password: `postgres`
- A JWT secret, for example: `local-dev-secret-at-least-32-characters-long`
- A current Node.js LTS release with npm for the frontend

## Local startup

From the project root:

```bash
cd findoc-agent
cp .env.example .env
./gradlew bootRun --console=plain
```

Set a unique `JWT_SECRET` in `.env` before starting the application. Add `GEMINI_API_KEY` and `OPENROUTER_API_KEY` when validating provider-backed embedding and generation flows. Gradle loads `.env` for `bootRun`, `test`, `localIntegrationTest`, and `integrationTest`; environment variables already supplied by your shell, CI system, or deployment platform take precedence.

`.env` is local and Git-ignored. Do not use it in CI or deployed environments; inject secrets through the environment or the platform secret manager instead.

### Configuration reference

The non-secret template is `findoc-agent/.env.example`. Important settings include:

- `DB_USERNAME`, `DB_PASSWORD`, and `KAFKA_BOOTSTRAP_SERVERS` configure the local PostgreSQL and Kafka connections.
- `TEST_DB_NAME` selects the PostgreSQL database used by `localIntegrationTest` and defaults to `findoc-test-db`. Optional `TEST_DB_URL`, `TEST_DB_USERNAME`, and `TEST_DB_PASSWORD` values can override the derived test connection without changing application configuration.
- `GEMINI_API_KEY`, `GEMINI_EMBEDDING_MODEL`, and `GEMINI_BASE_URL` configure embeddings. A Gemini key is required for ingestion to create vector embeddings.
- `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`, and `OPENROUTER_BASE_URL` configure answer generation and comparison. With no key, the application uses its local summary/comparison fallback.
- `FINDOC_INGESTION_TOPIC`, `FINDOC_INGESTION_GROUP`, and `FINDOC_INGESTION_DLQ` configure the ingestion topic, consumer group, and dead-letter topic.
- Ingestion retries use exponential backoff: `FINDOC_INGESTION_RETRY_INITIAL_INTERVAL_MS` defaults to `1000`, `FINDOC_INGESTION_RETRY_MULTIPLIER` to `2.0`, `FINDOC_INGESTION_RETRY_MAX_INTERVAL_MS` to `10000`, and `FINDOC_INGESTION_RETRY_MAX_RETRIES` to `2`.
- Provider circuit breakers open after three failures for 30 seconds by default. Override those values with `GEMINI_CIRCUIT_FAILURE_THRESHOLD`, `GEMINI_CIRCUIT_OPEN_DURATION_SECONDS`, `OPENROUTER_CIRCUIT_FAILURE_THRESHOLD`, and `OPENROUTER_CIRCUIT_OPEN_DURATION_SECONDS`.
- `LOG_PATH` and `LOG_FILE` configure the rolling application log location and filename.
- `FINDOC_CORS_ALLOWED_ORIGINS` configures the browser origins allowed to call `/api/**`; it defaults to `http://localhost:5173`.

The frontend uses a separate non-secret file at `findoc-agent/frontend/.env`:

- `VITE_API_BASE_URL` sets the backend base URL used by browser API requests and defaults to `http://localhost:8080` in `.env.example`.

The application accepts uploads up to 20 MB (`max-file-size` and `max-request-size`). Agent retrieval defaults to five sources (`agent.top-k`) and the agent loop remains capped at five iterations (`agent.max-iterations`).

The application listens on:

- http://localhost:8080

## Frontend development

The basic frontend is a Vite + React + TypeScript application in `findoc-agent/frontend/`. It provides login, document upload/list/status polling/delete, document selection, and session-aware agent queries.

From the frontend directory:

```bash
cd findoc-agent/frontend
cp .env.example .env
npm ci
npm run dev
```

The development server listens on http://localhost:5173. The tracked frontend template sets `VITE_API_BASE_URL=http://localhost:8080`. For a different backend address, update `frontend/.env`.

The backend allows `http://localhost:5173` by default. When the frontend runs at another origin, set `FINDOC_CORS_ALLOWED_ORIGINS` in the backend `.env` to the allowed origin or comma-separated origins.

Frontend validation commands are:

```bash
npm run build
npm run lint
```

The basic frontend does not currently include screens for session history, document comparison, or query explanation; those remain available through the backend API.

The project uses Spring Security to protect API endpoints. Public endpoints are:

- `/actuator/health`
- `/api/v1/auth/token`
- `/v3/api-docs`
- `/swagger-ui/**`

All other API routes require a valid bearer token.

## OpenAPI and Swagger UI

With the backend running locally, access the generated API documentation at:

- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Swagger UI: http://localhost:8080/swagger-ui/index.html

The Swagger UI groups the implemented APIs under Authentication, Documents, and Agent. Click **Authorize** and enter the JWT returned by `/api/v1/auth/token` to try protected operations. The authorization value should use the bearer token returned by the authentication endpoint.

To inspect the raw specification from a terminal:

```bash
curl -i http://localhost:8080/v3/api-docs
```

## Seeded demo account

The database changelog seeds a demo tenant and user:

- Tenant ID: `00000000-0000-0000-0000-000000000001`
- Username: `demo@findoc.local`
- Password: `demo123`

## Health check

```bash
curl -i http://localhost:8080/actuator/health
```

Sample response:

```json
{
  "status": "UP"
}
```

## Authentication

Request:

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId": "00000000-0000-0000-0000-000000000001",
    "username": "demo@findoc.local",
    "password": "demo123"
  }'
```

Sample response:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9....",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "tenantId": "00000000-0000-0000-0000-000000000001"
}
```

Save the token for later requests:

```bash
TOKEN="<accessToken value>"
```

## Document APIs

### List documents

```bash
curl -i http://localhost:8080/api/v1/documents \
  -H "Authorization: Bearer $TOKEN"
```

Sample response:

```json
[
  {
    "documentId": "0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9",
    "filename": "sample.txt",
    "fileType": "text/plain",
    "status": "PENDING",
    "chunkCount": 3,
    "createdAt": "2026-08-28T16:30:12.345Z"
  }
]
```

### Upload a document

```bash
curl -i -X POST http://localhost:8080/api/v1/documents/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/sample.txt"
```

Sample response:

```json
{
  "documentId": "0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9",
  "filename": "sample.txt",
  "fileType": "text/plain",
  "status": "PENDING",
  "chunkCount": 3,
  "createdAt": "2026-08-28T16:30:12.345Z"
}
```

The upload endpoint returns HTTP 202 Accepted. The document starts in `PENDING` while a Kafka ingestion job extracts text, stores the page count, chunks content at 512 tokens with 50-token overlap, creates embeddings, and marks the document `READY`. Processing failures mark the document `FAILED` after retry handling; a document must be `READY` before it can provide indexed content for queries or comparisons.

### Check document status

```bash
curl -i http://localhost:8080/api/v1/documents/0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9/status \
  -H "Authorization: Bearer $TOKEN"
```

Sample response:

```json
{
  "documentId": "0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9",
  "filename": "sample.txt",
  "fileType": "text/plain",
  "status": "PENDING",
  "chunkCount": 3,
  "createdAt": "2026-08-28T16:30:12.345Z"
}
```

### Download the original document

```bash
curl -i http://localhost:8080/api/v1/documents/0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9/download \
  -H "Authorization: Bearer $TOKEN" \
  -o sample.txt
```

### Delete a document

```bash
curl -i -X DELETE http://localhost:8080/api/v1/documents/0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9 \
  -H "Authorization: Bearer $TOKEN"
```

Response is HTTP 204 No Content.

### Document errors

Common invalid, missing, or unauthenticated requests return Spring `ProblemDetail` JSON with `type`, `title`, `status`, and `detail` fields. Examples include HTTP 400 for unsupported or empty files, invalid query/comparison input, or documents that are not ready; HTTP 401 for missing/invalid credentials; and HTTP 404 for tenant-scoped resources that do not exist. Document lookup and download remain tenant-scoped by the claims in the bearer token.

## Agent query API

Use an omitted `sessionId` for the first question. Save the returned `sessionId` and provide it in a follow-up query.

`query` is required. `documentIds` and `sessionId` are optional: omit `documentIds` for tenant-scoped retrieval across the tenant's indexed documents, and omit `sessionId` to start a new session. Results are limited to the configured `agent.top-k` value, five by default. `pageNumber` may be absent for source content without page metadata.

Request:

```bash
curl -i -X POST http://localhost:8080/api/v1/agent/query \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "query": "Summarize the key financial risks in the uploaded document.",
    "documentIds": ["0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9"]
  }'
```

Sample response:

```json
{
  "queryId": "fce381db-c5d8-4a8e-b9d9-cf08436c522f",
  "sessionId": "6a1c86f1-674d-4b50-b8d5-6b5fef5c1be1",
  "answer": "Relevant content found in 3 chunks.",
  "intent": "SUMMARISE",
  "sources": [
    {
      "chunkId": "b5cfa642-e727-47f4-a574-511c25fc8c92",
      "documentId": "0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9",
      "filename": "sample.txt",
      "content": "This report highlights liquidity risk and margin pressure.",
      "similarityScore": 0.91,
      "pageNumber": 1
    }
  ],
  "stepsTaken": [
    "classify_intent",
    "vector_search",
    "generate_report"
  ],
  "confidence": 0.75
}
```

### Follow-up query

```bash
curl -i -X POST http://localhost:8080/api/v1/agent/query \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "query": "What evidence supports that conclusion?",
    "sessionId": "6a1c86f1-674d-4b50-b8d5-6b5fef5c1be1"
  }'
```

The response keeps the same `sessionId` and includes the new query's `queryId`. The frontend carries this session ID across follow-up questions.

### Session history

```bash
curl -i http://localhost:8080/api/v1/agent/sessions/6a1c86f1-674d-4b50-b8d5-6b5fef5c1be1 \
  -H "Authorization: Bearer $TOKEN"
```

Sample response:

```json
{
  "sessionId": "6a1c86f1-674d-4b50-b8d5-6b5fef5c1be1",
  "messages": [
    {
      "role": "user",
      "content": "Summarize the key financial risks in the uploaded document.",
      "createdAt": "2026-09-04T10:15:30.123Z"
    },
    {
      "role": "assistant",
      "content": "The main risks are liquidity pressure and margin erosion.",
      "createdAt": "2026-09-04T10:15:30.450Z"
    }
  ]
}
```

### Query explanation

```bash
curl -i http://localhost:8080/api/v1/agent/explain/fce381db-c5d8-4a8e-b9d9-cf08436c522f \
  -H "Authorization: Bearer $TOKEN"
```

Sample response:

```json
{
  "queryId": "fce381db-c5d8-4a8e-b9d9-cf08436c522f",
  "query": "Summarize the key financial risks in the uploaded document.",
  "intent": "SUMMARISE",
  "fullTrace": ["classify_intent", "vector_search", "generate_report"],
  "totalDurationMs": 324
}
```

### Compare two documents

Both documents must belong to the authenticated tenant and have `READY` status.

```bash
curl -i -X POST http://localhost:8080/api/v1/agent/compare \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "documentIdA": "0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9",
    "documentIdB": "f44d15a8-b94d-4085-b1c3-23db7a915cc9",
    "aspect": "termination clauses"
  }'
```

Comparison retrieves evidence independently for each document. Both IDs must be tenant-owned documents with `READY` status; the response returns separate `documentASources` and `documentBSources` arrays.

Sample response:

```json
{
  "queryId": "401f67a1-46b5-4a70-8ac2-0eec974b3c2a",
  "similarities": ["Both documents contain content relevant to termination clauses."],
  "differences": [
    "Document A: Termination requires 30 days written notice.",
    "Document B: Termination requires 90 days written notice."
  ],
  "summary": "Comparison completed for termination clauses.",
  "documentASources": [
    {
      "chunkId": "b5cfa642-e727-47f4-a574-511c25fc8c92",
      "documentId": "0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9",
      "filename": "agreement-a.txt",
      "content": "Termination requires 30 days written notice.",
      "similarityScore": 0.88,
      "pageNumber": 1
    }
  ],
  "documentBSources": []
}
```

## Verification checklist

Before you consider a local test successful, confirm:

1. PostgreSQL is reachable on `localhost:5432`.
2. The application starts with `./gradlew bootRun` without runtime exceptions.
3. `/actuator/health` returns HTTP 200.
4. `/api/v1/auth/token` returns a bearer token for the seeded demo user.
5. A document upload returns HTTP 202 and a `DocumentResponse` with a valid `documentId`.
6. A protected request without a token returns HTTP 401.
7. After the document is `READY`, an agent query returns non-empty structured `sources`, `queryId`, and `sessionId`.
8. `GET /api/v1/agent/sessions/{sessionId}` and `GET /api/v1/agent/explain/{queryId}` return HTTP 200 for IDs returned by the query endpoint.
9. Comparing two tenant-owned `READY` documents returns HTTP 200 and source arrays for both document scopes.
10. For live end-to-end verification, confirm Kafka ingestion, PostgreSQL/pgvector persistence and retrieval, and provider-backed Gemini/OpenRouter calls separately; unit tests and application startup do not prove those integrations.

For frontend verification, also confirm that login succeeds, documents can be selected and uploaded, status polling reaches `READY` or `FAILED`, and a follow-up query reuses the returned session.

## Notes

- This project is intentionally tenant-scoped. Tokens carry both `tenant_id` and `user_id` claims.
- The agent query path records `classify_intent`, `vector_search`, and `generate_report`; the configured five-iteration cap remains enforced even though dynamic tool selection is future work.
- OpenRouter generation and comparison fall back to deterministic local responses when the API key is blank, a provider call fails, or its circuit breaker is open. Gemini embedding does not have an equivalent fallback, so ingestion requires a working Gemini key and endpoint.
- Integration tests that use Testcontainers require a working Podman remote socket in this environment; when that socket is unavailable, validate the live PostgreSQL/Kafka workflow with separately running local services.
- The project includes a seeded demo tenant and user, but production deployments should rely on a proper secret manager and database administration workflow.
- The basic frontend has build and lint validation but no automated frontend tests yet; this remains a future enhancement.
