# FinDoc Agent

Tenant-aware document intelligence backend built with Spring Boot 3.2 and Java 17. It persists documents and sources in PostgreSQL, ingests asynchronously through Kafka, retrieves with pgvector cosine similarity, and generates grounded agent responses with source citations.

## Local Run

```bash
cp .env.example .env
./gradlew bootRun --console=plain
```

PostgreSQL with pgvector must be available on port 5432 and Kafka on port 9092. Set a unique `JWT_SECRET` in the local `.env`; provider credentials are needed only for live embedding/generation validation. Do not commit a local dotenv file.

Run unit tests with `./gradlew test --console=plain`. `./gradlew integrationTest --console=plain` uses Testcontainers and requires an accessible Podman socket.

The seeded local-only demo account uses tenant ID `00000000-0000-0000-0000-000000000001`, username `demo@findoc.local`, and password `demo123`. API requests, endpoint contracts, and current runtime caveats are maintained in the [root README](../README.md) and [developer guide](../docs/dev-guide/README.md).
