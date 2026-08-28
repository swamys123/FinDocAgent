# FinDoc Agent developer guide

## Current status

Basic sanity is currently verified in this workspace:

- `cd findoc-agent && ./gradlew test --console=plain` completed successfully.
- `cd findoc-agent && ./gradlew bootRun --console=plain` started the Spring Boot app and initialized Tomcat on port 8080.

This means the project is runnable locally in its current state as long as PostgreSQL is available and the expected environment variables are set.

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
export JWT_SECRET=local-dev-secret-at-least-32-characters-long
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
./gradlew bootRun --console=plain
```

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

Request:

```bash
curl -i -X POST http://localhost:8080/api/v1/agent/query \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "query": "Summarize the key financial risks in the uploaded document.",
    "documentIds": ["0e5dc0a9-f855-4b46-a2b9-6f4d88cee0b9"],
    "sessionId": "6a1c86f1-674d-4b50-b8d5-6b5fef5c1be1"
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
    "This report highlights liquidity risk and margin pressure.",
    "The operating cash flow has been volatile over the past two quarters."
  ],
  "stepsTaken": [
    "classify_intent",
    "vector_search",
    "generate_report"
  ],
  "confidence": 0.75
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

## Notes

- This project is intentionally tenant-scoped. Tokens carry both `tenant_id` and `user_id` claims.
- The agent query path is guarded by the five-iteration cap configured in `agent.max-iterations`.
- The project includes a seeded demo tenant and user, but production deployments should rely on a proper secret manager and database administration workflow.
