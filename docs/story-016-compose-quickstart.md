# Story 016: Full-Stack Compose Quickstart

## Status

Complete; runtime verified with Docker Compose.

## Completed Work

- Added a root Compose definition for PostgreSQL/pgvector, single-node KRaft Kafka, the Gradle 8.10.2/JDK 17 backend, and the Node 22/Vite frontend.
- Routed backend database and Kafka connections over the Compose network and loaded backend settings/provider credentials from `findoc-agent/.env`.
- Added database and Kafka health checks, persistent PostgreSQL/upload volumes, and host port defaults that avoid the existing local PostgreSQL/Kafka ports.
- Documented start, stop, and intentional reset commands in both READMEs and replaced the obsolete future Compose sketch in the specification.
- Started the full stack successfully: all four services are running, PostgreSQL and Kafka report healthy, backend health and demo auth return HTTP 200, frontend returns HTTP 200, and the ingestion consumer is registered with Kafka.

## Pending Work

- Verify database and upload persistence across a Compose restart.
- Verify a document ingestion end to end with a valid Gemini API key.

## Validation Performed

- `docker compose config --quiet` passed without printing resolved environment values.
- `docker compose --env-file findoc-agent/.env config --quiet` passed without printing resolved environment values.
- `docker compose --env-file findoc-agent/.env up -d` started all four services successfully.
- PostgreSQL and Kafka health checks passed; the backend health endpoint and seeded demo auth both returned HTTP 200; the frontend returned HTTP 200; Kafka reported the `findoc-ingestion` consumer group.

## Next Implementation Item

Verify Gemini-backed ingestion and volume persistence across restart, then continue with operational metrics and end-to-end multi-tenant load validation.