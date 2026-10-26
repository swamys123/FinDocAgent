# FinDoc Agent Developer Guide

## Current status

The implementation is now beyond the initial scaffold and includes the main tenant-aware backend flow for document ingestion and agent querying.

Previously recorded validation (not rerun during this documentation review):

- `cd findoc-agent && ./gradlew test --console=plain` passed.
- `cd findoc-agent && ./gradlew localIntegrationTest --console=plain` passed against locally running PostgreSQL/pgvector and Kafka. Live provider tests in this task are skipped when their API keys are absent.
- The Audit 1 cases in `cd findoc-agent && ./gradlew correctnessTest --tests com.findoc.correctness.UploadPublishAtomicityIntegrationTest --console=plain` pass using Testcontainers.
- The Audit 2 cases in `cd findoc-agent && ./gradlew correctnessTest --tests com.findoc.correctness.IngestionIdempotencyIntegrationTest --console=plain` pass using Testcontainers.
- The Audit 4 idempotency-key cases in `cd findoc-agent && ./gradlew correctnessTest --tests com.findoc.correctness.UploadIdempotencyIntegrationTest --console=plain` pass using Testcontainers.
- `cd findoc-agent && ./gradlew bootRun --console=plain` started the Spring Boot app and initialized Tomcat on port 8080.

Implemented so far:

- Gradle-based Spring Boot 3.2 Java 17 application scaffold
- Tenant-aware JWT authentication with both `tenant_id` and `user_id` claims
- Protected document APIs for listing, uploading, checking status, and deleting documents
- Tenant-scoped persistence and seeded demo tenant/user data
- Text/PDF extraction with PDF page-count persistence and document source handling; chunk-level page numbers are not currently assigned
- Chunking at up to 512 whitespace-delimited terms with 50-term overlap; counts use whitespace splitting, not a model tokenizer
- pgvector-backed similarity search using cosine distance (`<=>`) with tenant and document scoping
- Kafka ingestion producer/consumer wiring with retry/DLQ handling checks
- Fixed three-stage agent query flow with intent classification, vector retrieval, and structured citations
- Tenant-scoped session history and query-trace explanation endpoints, plus a recent-query trace screen in the frontend
- Tenant-safe document comparison with independent retrieval for each document
- Optional tenant-scoped HTTP idempotency keys for upload, query, and comparison; tenant-admin replay for failed ingestion jobs
- Session and trace persistence with request-scoped MDC logging for `trace_id`, `tenant_id`, and `user_id`
- OpenRouter-backed generation with structured comparison fallback and response validation
- React frontend with login, document management, status polling, document selection, session-aware queries, and recent-query trace explanations
- Backend CORS configuration for the local frontend origin, plus successful frontend build and lint validation
- Generated OpenAPI documentation and Swagger UI with bearer JWT security metadata

Current runtime caveats:

- `integrationTest` and `localIntegrationTest` use the local PostgreSQL/Kafka settings; `localIntegrationTest` also includes live-provider tests, which skip when the corresponding Gemini/OpenRouter key is absent. The tagged `correctnessTest` suite uses Testcontainers and requires an accessible Docker socket.
- The correctness suite covers outbox delivery/recovery, ingestion idempotency, poison messages, dead-letter handling, upload idempotency, and unresolved audit cases. Focused cases have previously passed as listed above, but the full suite is not guaranteed to pass while Audit 3 DLQ delivery safety/observability and Audit 4 content-hash deduplication remain unfinished. If Docker 29 rejects the Testcontainers Docker API negotiation, `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` is a recorded workaround; verify compatibility with the installed Docker/Testcontainers versions before relying on it.

## Prerequisites

- Java 17
- PostgreSQL running locally on `localhost:5432`
- Database name: `findoc`
- Database user: `postgres`
- Database password: `postgres`
- A JWT secret, for example: `local-dev-secret-at-least-32-characters-long`
- A current Node.js LTS release with npm for the frontend

## Local Setup

From the project root:

```bash
cd findoc-agent
cp -n .env.example .env
./gradlew bootRun --console=plain
```

Set a unique `JWT_SECRET` in `.env` before starting the application. Add `GEMINI_API_KEY` and `OPENROUTER_API_KEY` when validating provider-backed embedding and generation flows. Gradle loads `.env` for `bootRun`, `test`, `localIntegrationTest`, and `integrationTest`; environment variables already supplied by your shell, CI system, or deployment platform take precedence. The `-n` option preserves any existing local `.env`.

`.env` is local and Git-ignored. Do not use it in CI or deployed environments; inject secrets through the environment or the platform secret manager instead.

### Distributed-systems correctness tests

Run the full correctness suite from `findoc-agent/`:

```bash
./gradlew correctnessTest --console=plain
```

This task selects JUnit tests tagged `correctness` and starts PostgreSQL/pgvector and Kafka with Testcontainers. It needs an accessible Docker socket and does not require live Gemini or OpenRouter credentials. These are integration checks of real persistence and asynchronous messaging paths, not just controller-level tests. At a high level, they validate transactional-outbox delivery and recovery; ingestion idempotency, retry and concurrent-delivery behavior; staged embedding reuse; chunk uniqueness and upserts; stale-claim recovery; poison-record and dead-letter handling; and upload idempotency.

Idempotency keys are supported on three endpoints: document upload, agent query, and document comparison. The coverage checks response replay on the endpoints and, against PostgreSQL, verifies that an identical request under the same tenant/key replays the stored response, a changed request returns HTTP 422, concurrent requests are resolved by the database uniqueness constraint, the key is isolated by tenant, and a key can be reused after its 24-hour expiry. It also verifies that a failed operation rolls back its key reservation. These tests are separate from the standard `test` task.

Run the upload idempotency class on its own:

```bash
./gradlew correctnessTest --tests com.findoc.correctness.UploadIdempotencyIntegrationTest --console=plain
```

To run the Audit 2 ingestion-idempotency class on its own:

```bash
./gradlew correctnessTest --tests com.findoc.correctness.IngestionIdempotencyIntegrationTest --console=plain
```

This class exercises database uniqueness for live chunks, re-ingestion upserts and stale-tail cleanup, tenant-scoped claims, and atomic handling of concurrent or duplicate deliveries. It also checks that embedding-provider calls happen outside database transactions, successful embeddings are reused after a retry or stale-claim recovery, deletion during ingestion does not restore deleted data, and stale claims are requeued through the transactional outbox. Supporting correctness tests separately verify that malformed Kafka records reach the DLQ without blocking later valid ingestion and that exhausted delivery attempts are recorded accurately. Audit 1, Audit 2 ingestion-idempotency, and the implemented Audit 4 idempotency-key cases pass; unfinished findings in Audit 3 and the remaining Audit 4 content-hash deduplication checks may still cause failures in the full suite.

If Docker reports that the API version used by the installed Testcontainers dependency is too old, use:

```bash
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew correctnessTest --console=plain
```

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

The application accepts uploads up to 20 MB (`max-file-size` and `max-request-size`). Agent retrieval defaults to five sources (`agent.top-k`). The configuration declares `agent.max-iterations=5` and clamps the value to at most five, but the current query path is a fixed classify/retrieve/generate sequence rather than an iterative tool loop.

The application listens on:

- http://localhost:8080

## Frontend development

The basic frontend is a Vite + React + TypeScript application in `findoc-agent/frontend/`. It provides login, document upload/list/status polling/delete, document selection, and session-aware agent queries.

From the frontend directory:

```bash
cd findoc-agent/frontend
cp -n .env.example .env
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

The frontend includes a Recent Queries screen with expandable explanations for recorded traces. It does not currently include session-history or document-comparison screens; those remain available through the backend API.

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

The Swagger UI groups the implemented APIs under Authentication, Documents, Agent, and Administration. Click **Authorize** and enter the JWT returned by `/api/v1/auth/token` to try protected operations. Administration operations additionally require a tenant administrator role.

To inspect the raw specification from a terminal:

```bash
curl -i http://localhost:8080/v3/api-docs
```

## Demo Login (Seeded Account)

The database changelog seeds a demo tenant and user. The seeded password hash has been verified against `demo123`:

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

The JSON responses below show API shapes; example IDs, timestamps, scores, durations, and generated text are illustrative unless explicitly described as configured values.

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
    "chunkCount": 0,
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
  "chunkCount": 0,
  "createdAt": "2026-08-28T16:30:12.345Z"
}
```

The upload endpoint returns HTTP 202 Accepted. The document starts in `PENDING` while a Kafka ingestion job extracts text, stores the PDF page count, splits the extracted text into chunks of up to 512 whitespace-delimited terms with 50-term overlap, creates embeddings, and marks the document `READY`. The chunk count uses whitespace splitting, not a model tokenizer. Page counts are persisted, but the current ingestion path does not assign page numbers to individual chunks, so the source `pageNumber` field can be `null`. Processing failures mark the document `FAILED` after retry handling; a document must be `READY` before it can provide indexed content for queries or comparisons.

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
  "chunkCount": 0,
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

### Replay a failed ingestion job

Tenant administrators can request replay of a failed document through the transactional outbox. The endpoint derives the tenant from the JWT and reuses the document's stored owner; callers cannot supply either identifier. Repeating the request while the document is already `PENDING` does not enqueue another job.

```bash
curl -i -X POST http://localhost:8080/api/v1/admin/dlq/0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9/replay \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The endpoint returns HTTP 202 when replay is queued or already pending, HTTP 403 unless the authenticated user has the `ADMIN` role, HTTP 404 for a missing or cross-tenant document, and HTTP 409 when the document is processing or already ready. The seeded demo account has the `USER` role and cannot use this endpoint. Role assignment is a trusted provisioning task; there is no self-service role elevation API. DLQ publish atomicity, retry recovery, topic provisioning, and operational observability remain pending audit work.

## Agent Query API (Ask Questions)

Use an omitted `sessionId` for the first question. Save the returned `sessionId` and provide it in a follow-up query.

`query` is required. `documentIds` and `sessionId` are optional: omit `documentIds` for tenant-scoped retrieval across the tenant's indexed documents, and omit `sessionId` to start a new session. Results are limited to the configured `agent.top-k` value, five by default. PDF page counts are stored, but the current ingestion path does not set chunk-level page numbers, so source `pageNumber` values are currently `null`.

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
  "answer": "[Illustrative generated answer based on retrieved content.]",
  "intent": "SUMMARISE",
  "sources": [
    {
      "chunkId": "b5cfa642-e727-47f4-a574-511c25fc8c92",
      "documentId": "0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9",
      "filename": "sample.txt",
      "content": "This report highlights liquidity risk and margin pressure.",
      "similarityScore": 0.91,
      "pageNumber": null
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

### Query Explanation (Trace)

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
  "fullTrace": [
    {
      "step": 1,
      "tool": "classify_intent",
      "input": { "query": "Summarize the key financial risks in the uploaded document." },
      "output": { "intent": "SUMMARISE" },
      "durationMs": 2
    },
    {
      "step": 2,
      "tool": "vector_search",
      "input": { "topK": 5, "documentIds": [] },
      "output": { "chunksFound": 2, "topScore": 0.87, "scores": [0.87, 0.83] },
      "durationMs": 24
    },
    {
      "step": 3,
      "tool": "generate_report",
      "input": { "chunkCount": 2, "format": "summary" },
      "output": { "answerLength": 634 },
      "durationMs": 298
    }
  ],
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
      "pageNumber": null
    }
  ],
  "documentBSources": [
    {
      "chunkId": "5d5dd6dd-43fd-4e62-b4cb-e194e2c1df63",
      "documentId": "f44d15a8-b94d-4085-b1c3-23db7a915cc9",
      "filename": "agreement-b.txt",
      "content": "Termination requires 90 days written notice.",
      "similarityScore": 0.86,
      "pageNumber": null
    }
  ]
}
```

## Optional HTTP idempotency keys

The upload, agent query, and document comparison `POST` endpoints accept an optional `Idempotency-Key` header. Keys are scoped to the authenticated tenant, must be non-blank and at most 255 characters, and expire after 24 hours. A repeated request with the same key and request fingerprint replays the stored status and response; reusing the key with a different request returns HTTP 422. Responses for keyed requests include `Idempotent-Replayed: true` or `false`. These headers are allowed/exposed by the configured API CORS policy. The frontend does not currently attach idempotency keys or implement keyed retry handling.

## Verification checklist

Before you consider a local test successful, confirm:

1. PostgreSQL is reachable on `localhost:5432`.
2. The application starts with `./gradlew bootRun` without runtime exceptions.
3. `/actuator/health` returns HTTP 200.
4. `/api/v1/auth/token` returns a bearer token for the seeded demo user.
5. A document upload returns HTTP 202 and a `DocumentResponse` with a valid `documentId`.
6. A protected request without a token returns HTTP 401.
7. After the document is `READY`, an agent query returns non-empty structured `sources`, `queryId`, and `sessionId`.
8. `GET /api/v1/agent/sessions/{sessionId}`, `GET /api/v1/agent/explain/{queryId}`, and `GET /api/v1/agent/traces/recent` return HTTP 200 for the authenticated user's records.
9. Comparing two tenant-owned `READY` documents returns HTTP 200 and includes separate source arrays for the two document scopes.
10. For live end-to-end verification, confirm Kafka ingestion, PostgreSQL/pgvector persistence and retrieval, and provider-backed Gemini/OpenRouter calls separately; unit tests and application startup do not prove those integrations.

For frontend verification, also confirm that login succeeds, documents can be selected and uploaded, status polling reaches `READY` or `FAILED`, follow-up queries reuse the returned session, and the Recent Queries screen lists and expands trace details.

## Notes

- This project is intentionally tenant-scoped. Tokens carry both `tenant_id` and `user_id` claims.
- The current query path is a fixed classify, vector-search, and generation sequence with three recorded trace steps; it is not an iterative tool loop. `agent.max-iterations` is configured as 5 and clamped to at most 5, but is not used to drive this fixed sequence.
- OpenRouter generation and comparison fall back to deterministic local responses when the API key is blank, a provider call fails, or its circuit breaker is open. Gemini embedding does not have an equivalent fallback, so ingestion requires a working Gemini key and endpoint.
- Integration tests that use Testcontainers require a working Docker socket; when the daemon is unavailable, validate the live PostgreSQL/Kafka workflow with separately running local services.
- The project includes a seeded demo tenant and user, but production deployments should rely on a proper secret manager and database administration workflow.
- The frontend provides a recent-query list and expandable trace explanations; automated frontend tests remain a future enhancement.
