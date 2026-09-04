# FinDoc Agent developer guide

## Current status

The implementation is now beyond the initial scaffold and includes the main tenant-aware backend flow for document ingestion and agent querying.

Verified in this workspace:

- `cd findoc-agent && ./gradlew test --console=plain` passed successfully.
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

Current runtime caveat:

- The project is runnable locally in a boot/sanity sense, but real PostgreSQL/pgvector and Kafka integration validation remains the next live environment checkpoint before calling the end-to-end flow completely production-verified.

## Prerequisites

- Java 17
- PostgreSQL running locally on `localhost:5432`
- Database name: `findoc`
- Database user: `postgres`
- Database password: `postgres`
- A JWT secret, for example: `local-dev-secret-at-least-32-characters-long`

## Local startup

From the project root:

```bash
cd findoc-agent
cp .env.example .env
./gradlew bootRun --console=plain
```

Set a unique `JWT_SECRET` in `.env` before starting the application. Add `GEMINI_API_KEY` and `OPENROUTER_API_KEY` when validating provider-backed embedding and generation flows. Gradle loads `.env` for `bootRun`, `test`, and `integrationTest`; environment variables already supplied by your shell, CI system, or deployment platform take precedence.

`.env` is local and Git-ignored. Do not use it in CI or deployed environments; inject secrets through the environment or the platform secret manager instead.

The application listens on:

- http://localhost:8080

The project uses Spring Security to protect API endpoints. Public endpoints are:

- `/actuator/health`
- `/api/v1/auth/token`

All other API routes require a valid bearer token.

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

### Delete a document

```bash
curl -i -X DELETE http://localhost:8080/api/v1/documents/0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9 \
  -H "Authorization: Bearer $TOKEN"
```

Response is HTTP 204 No Content.

## Agent query API

Use an omitted `sessionId` for the first question. Save the returned `sessionId` and provide it in a follow-up query.

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
5. A document upload returns a `DocumentResponse` with a valid `documentId`.
6. A protected request without a token returns HTTP 401.
7. After the document is `READY`, an agent query returns non-empty structured `sources`, `queryId`, and `sessionId`.
8. `GET /api/v1/agent/sessions/{sessionId}` and `GET /api/v1/agent/explain/{queryId}` return HTTP 200 for IDs returned by the query endpoint.
9. Comparing two tenant-owned `READY` documents returns HTTP 200 and source arrays for both document scopes.

## Notes

- This project is intentionally tenant-scoped. Tokens carry both `tenant_id` and `user_id` claims.
- The agent query path is guarded by the five-iteration cap configured in `agent.max-iterations`.
- The project includes a seeded demo tenant and user, but production deployments should rely on a proper secret manager and database administration workflow.
