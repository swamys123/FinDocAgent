# FinDoc Agent

Gradle Spring Boot 3.2 POC using Java 17.

## Run

```bash
export JWT_SECRET=local-dev-secret-at-least-32-characters-long
./gradlew bootRun
```

Run tests with `./gradlew test`. Authenticate with `POST /api/v1/auth/token` using
tenant id `00000000-0000-0000-0000-000000000001`, username `demo@findoc.local`,
and password `demo123`, then upload text or PDF content through
`POST /api/v1/documents/upload`.

The current first implementation slice provides JWT tenant claims, tenant-scoped
in-memory document ownership, 512-token chunking with 50-token overlap, and a
five-iteration-capped agent response flow. PostgreSQL/pgvector, Kafka ingestion,
Gemini, OpenRouter, and the remaining API contracts are the next implementation
slice.
