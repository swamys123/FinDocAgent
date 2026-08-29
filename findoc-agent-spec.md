# FinDoc Agent — Complete POC Specification
> Agentic Document Intelligence Backend  
> Target: Local Dev Environment  
> Stack: Java 17 · Spring Boot 3.x · PostgreSQL + pgvector · Apache Kafka · Gemini Embeddings · OpenRouter LLM

---

## 1. Project Overview

FinDoc Agent is a production-quality backend POC that accepts PDF and text documents, indexes them using vector embeddings, and answers natural language queries using an agentic reasoning loop — not just a single RAG call. The agent decides which tools to invoke (vector search, metadata lookup, document comparison, report generation) based on user intent, producing structured, auditable responses.

**Target personas (Singapore market):**
- Fintech companies (DBS, Grab Financial, SeaMoney) — contract and compliance document review
- GovTech / public sector — policy document Q&A
- SI firms (NCS, Accenture SG) — white-label document intelligence

---

## 2. Functional Requirements

### 2.1 Document Management

| ID | Requirement |
|----|-------------|
| FR-01 | System SHALL accept PDF and plain-text (.txt) file uploads via multipart POST |
| FR-02 | Each uploaded document SHALL be assigned a UUID and stored with metadata (filename, upload time, tenant ID, page count, status) |
| FR-03 | Document ingestion SHALL be asynchronous — the upload endpoint returns a job ID immediately |
| FR-04 | System SHALL chunk documents into segments of ~512 tokens with a 50-token overlap |
| FR-05 | Each chunk SHALL be embedded using Gemini text-embedding-004 (768 dimensions) and stored in pgvector |
| FR-06 | System SHALL expose a status endpoint to poll ingestion progress (PENDING → PROCESSING → READY → FAILED) |
| FR-07 | System SHALL support listing all documents scoped to the authenticated tenant |
| FR-08 | System SHALL support soft-deleting a document and its associated chunks |

### 2.2 Agentic Query

| ID | Requirement |
|----|-------------|
| FR-09 | System SHALL accept a natural language query with an optional document scope (one doc, a list, or all docs in tenant) |
| FR-10 | System SHALL classify query intent: LOOKUP, COMPARE, SUMMARISE, or REPORT |
| FR-11 | Agent loop SHALL select and invoke tools based on intent (see Tool Definitions, §2.4) |
| FR-12 | Agent SHALL support a maximum of 5 tool-call iterations per query to prevent infinite loops |
| FR-13 | System SHALL return a structured JSON response: answer, sources (chunk IDs + scores), steps_taken, and confidence |
| FR-14 | System SHALL maintain a session context so follow-up questions can reference prior answers |
| FR-15 | System SHALL expose a /explain endpoint that returns the full agent trace for a given query ID |

### 2.3 Document Comparison

| ID | Requirement |
|----|-------------|
| FR-16 | System SHALL accept two document IDs and a comparison question |
| FR-17 | System SHALL retrieve the top-k relevant chunks from each document independently |
| FR-18 | System SHALL produce a structured diff-style response: similarities, differences, and a summary |

### 2.4 Agent Tool Definitions

The agent has access to the following tools, described as function signatures passed to the LLM:

```
vector_search(query: string, document_ids: string[], top_k: int) → Chunk[]
  Search the vector store for semantically similar chunks.

get_document_metadata(document_id: string) → DocumentMeta
  Return structured metadata: title, page count, upload date, tenant.

compare_documents(doc_id_a: string, doc_id_b: string, aspect: string) → ComparisonResult
  Run parallel vector searches and return a structured comparison.

generate_report(query: string, chunks: Chunk[], format: "summary"|"bullets"|"structured") → Report
  Instruct the LLM to produce a final formatted report from retrieved context.

get_session_history(session_id: string) → Message[]
  Retrieve prior messages in this session for follow-up context.
```

### 2.5 Auth

| ID | Requirement |
|----|-------------|
| FR-19 | All endpoints (except /health and /api-docs) SHALL require a valid JWT Bearer token |
| FR-20 | JWT SHALL carry tenant_id and user_id claims used for data scoping |
| FR-21 | System SHALL expose POST /auth/token accepting { tenantId, username, password } returning a signed JWT for local dev testing (no external IdP needed for POC) |

---

## 3. Non-Functional Requirements

### 3.1 Performance
| ID | Requirement |
|----|-------------|
| NFR-01 | Vector similarity search SHALL return results within 500ms for a corpus of up to 10,000 chunks |
| NFR-02 | Document upload endpoint SHALL respond within 200ms (async — does not wait for ingestion) |
| NFR-03 | Agent query endpoint SHALL respond within 15 seconds (includes up to 5 LLM tool calls) |

### 3.2 Reliability
| ID | Requirement |
|----|-------------|
| NFR-04 | Failed ingestion jobs SHALL be retried up to 3 times with exponential backoff via Kafka consumer |
| NFR-05 | Dead-letter topic SHALL capture permanently failed ingestion jobs |
| NFR-06 | All external API calls (Gemini, OpenRouter) SHALL have a 10-second timeout with circuit-breaker fallback |

### 3.3 Observability
| ID | Requirement |
|----|-------------|
| NFR-07 | Every agent query SHALL generate a trace_id logged through all steps |
| NFR-08 | Application SHALL expose Spring Actuator health and metrics endpoints |
| NFR-09 | Structured JSON logging (Logback) with trace_id, tenant_id, and duration on every request |

### 3.4 Security
| ID | Requirement |
|----|-------------|
| NFR-10 | All vector searches SHALL be filtered by tenant_id — cross-tenant data leakage is not permitted |
| NFR-11 | File uploads SHALL be validated: max 20MB, allowed MIME types only (application/pdf, text/plain) |
| NFR-12 | JWT secret SHALL be loaded from environment variable, never hardcoded |
| NFR-13 | SQL queries SHALL use parameterised statements only — no string concatenation |

### 3.5 Developer Experience
| ID | Requirement |
|----|-------------|
| NFR-14 | Entire stack SHALL start with a single `docker compose up` command |
| NFR-15 | Application SHALL auto-run Liquibase migrations on startup |
| NFR-16 | OpenAPI (Swagger UI) SHALL be available at /swagger-ui.html |
| NFR-17 | A Postman collection or sample curl script SHALL be included for all endpoints |

---

## 4. Technology Stack

| Layer | Technology | Notes |
|-------|-----------|-------|
| Language | Java 17 | LTS, matches your resume |
| Framework | Spring Boot 3.2.x | Web, Security, Actuator, Data JPA |
| Vector DB | PostgreSQL 16 + pgvector 0.7 | Single DB, no extra service |
| Migrations | Liquibase | Already in your stack |
| Messaging | Apache Kafka (via Docker) | Async ingestion pipeline |
| Embeddings | Google Gemini text-embedding-004 | Free tier — 1,500 req/day |
| LLM | OpenRouter (Mistral-7B-Instruct) | Free tier |
| PDF Parsing | Apache PDFBox 3.x | Zero cost, no API key |
| Auth | Spring Security + JJWT | Self-contained for POC |
| Build | Gradle 8.x | Gradle wrapper, Java 17 toolchain |
| Container | Docker + Docker Compose | Local dev only |
| API Docs | SpringDoc OpenAPI 2.x | Auto-generates from annotations |
| Testing | JUnit 5 + Mockito | Already in your stack |

---

## 5. Data Model

### 5.1 Liquibase Migration — Full Schema

```sql
-- changelog: 001-init.sql

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Tenants (minimal, for multi-tenant scoping)
CREATE TABLE tenants (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Users (local dev auth only)
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    username    VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,  -- BCrypt hashed
    role        VARCHAR(50) NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Documents
CREATE TABLE documents (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    user_id      UUID NOT NULL REFERENCES users(id),
    filename     VARCHAR(500) NOT NULL,
    file_type    VARCHAR(50) NOT NULL,  -- 'PDF' | 'TEXT'
    page_count   INTEGER,
    status       VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    -- PENDING | PROCESSING | READY | FAILED
    error_msg    TEXT,
    retry_count  INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMP  -- soft delete
);

-- Document chunks with embeddings
CREATE TABLE document_chunks (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id  UUID NOT NULL REFERENCES documents(id),
    tenant_id    UUID NOT NULL REFERENCES tenants(id),  -- denormalised for RLS
    chunk_index  INTEGER NOT NULL,
    content      TEXT NOT NULL,
    token_count  INTEGER,
    embedding    vector(768),  -- Gemini text-embedding-004 dimension
    metadata     JSONB,        -- page number, section title, etc.
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Index for fast similarity search scoped by tenant
CREATE INDEX ON document_chunks
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE INDEX ON document_chunks (tenant_id, document_id);

-- Agent query sessions
CREATE TABLE agent_sessions (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    user_id      UUID NOT NULL REFERENCES users(id),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Individual messages within a session (for follow-up awareness)
CREATE TABLE session_messages (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id   UUID NOT NULL REFERENCES agent_sessions(id),
    role         VARCHAR(20) NOT NULL,  -- 'user' | 'assistant'
    content      TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Agent query audit log (full trace for /explain endpoint)
CREATE TABLE query_traces (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id   UUID NOT NULL REFERENCES agent_sessions(id),
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    query        TEXT NOT NULL,
    intent       VARCHAR(50),           -- LOOKUP | COMPARE | SUMMARISE | REPORT
    steps        JSONB,                 -- full tool-call trace
    answer       TEXT,
    confidence   DECIMAL(4,3),
    duration_ms  INTEGER,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Seed data for local dev
INSERT INTO tenants (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'demo-tenant');

INSERT INTO users (id, tenant_id, username, password, role) VALUES
    ('00000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000001',
     'demo@findoc.local',
     '$2b$12$V5X/xLpkgnkf2wOpOeqFD.Lk9UvCVsawzxxhqGg.iZkc91Tn7LThK',  -- password: demo123
     'USER');
```

---

## 6. API Specification

### Base URL (local): `http://localhost:8080/api/v1`

---

### 6.1 Auth

**POST /auth/token**
```json
// Request
{ "username": "demo@findoc.local", "password": "demo123" }

// Response 200
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "tenantId": "00000000-0000-0000-0000-000000000001"
}
```

---

### 6.2 Document Upload

**POST /documents/upload**
```
Headers: Authorization: Bearer <token>
Content-Type: multipart/form-data
Body: file=<binary>
```
```json
// Response 202 Accepted
{
  "documentId": "3f7a1b2c-...",
  "status": "PENDING",
  "message": "Document queued for ingestion"
}
```

**GET /documents/{id}/status**
```json
// Response 200
{
  "documentId": "3f7a1b2c-...",
  "filename": "loan-agreement.pdf",
  "status": "READY",
  "pageCount": 12,
  "chunkCount": 47,
  "createdAt": "2026-08-27T10:00:00Z"
}
```

**GET /documents**
```json
// Response 200
{
  "documents": [
    {
      "documentId": "3f7a1b2c-...",
      "filename": "loan-agreement.pdf",
      "status": "READY",
      "pageCount": 12,
      "createdAt": "2026-08-27T10:00:00Z"
    }
  ],
  "total": 1
}
```

**DELETE /documents/{id}**
```json
// Response 200
{ "message": "Document deleted", "documentId": "3f7a1b2c-..." }
```

---

### 6.3 Agentic Query

**POST /agent/query**
```json
// Request
{
  "query": "What are the penalty clauses in this loan agreement?",
  "documentIds": ["3f7a1b2c-..."],   // optional — omit to search all tenant docs
  "sessionId": "9a8b7c6d-..."        // optional — omit to start a new session
}

// Response 200
{
  "queryId": "1a2b3c4d-...",
  "sessionId": "9a8b7c6d-...",
  "answer": "The loan agreement contains three penalty clauses...",
  "sources": [
    {
      "chunkId": "abc123",
      "documentId": "3f7a1b2c-...",
      "filename": "loan-agreement.pdf",
      "content": "...relevant chunk text...",
      "similarityScore": 0.91,
      "pageNumber": 7
    }
  ],
  "intent": "LOOKUP",
  "stepsTaken": [
    { "tool": "vector_search", "input": "penalty clauses", "chunksFound": 5 },
    { "tool": "generate_report", "format": "summary" }
  ],
  "confidence": 0.87,
  "durationMs": 3241
}
```

**POST /agent/compare**
```json
// Request
{
  "documentIdA": "3f7a1b2c-...",
  "documentIdB": "5e6f7a8b-...",
  "aspect": "termination clauses"
}

// Response 200
{
  "queryId": "2b3c4d5e-...",
  "similarities": ["Both documents require 30 days written notice..."],
  "differences": [
    "Document A allows termination for convenience; Document B does not",
    "Document B includes a 3-month lock-in period absent from Document A"
  ],
  "summary": "The agreements differ significantly in termination rights...",
  "sources": { "documentA": [...], "documentB": [...] }
}
```

**GET /agent/sessions/{sessionId}**
```json
// Response 200
{
  "sessionId": "9a8b7c6d-...",
  "messages": [
    { "role": "user", "content": "What are the penalty clauses?", "createdAt": "..." },
    { "role": "assistant", "content": "The loan agreement contains...", "createdAt": "..." }
  ]
}
```

**GET /agent/explain/{queryId}**
```json
// Response 200
{
  "queryId": "1a2b3c4d-...",
  "query": "What are the penalty clauses?",
  "intent": "LOOKUP",
  "fullTrace": [
    {
      "step": 1,
      "tool": "vector_search",
      "input": { "query": "penalty clauses loan agreement", "topK": 5 },
      "output": { "chunksFound": 5, "topScore": 0.91 },
      "durationMs": 312
    },
    {
      "step": 2,
      "tool": "generate_report",
      "input": { "chunkCount": 5, "format": "summary" },
      "output": { "answerLength": 420 },
      "durationMs": 2890
    }
  ],
  "totalDurationMs": 3241
}
```

---

## 7. Component Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                    │
│                                                               │
│  ┌─────────────┐    ┌──────────────┐    ┌────────────────┐  │
│  │   Auth       │    │  Document    │    │  Agent         │  │
│  │   Controller │    │  Controller  │    │  Controller    │  │
│  └──────┬──────┘    └──────┬───────┘    └───────┬────────┘  │
│         │                  │                     │           │
│  ┌──────▼──────┐    ┌──────▼───────┐    ┌───────▼────────┐  │
│  │   JWT        │    │  Document    │    │  Agent         │  │
│  │   Service    │    │  Service     │    │  Service       │  │
│  └─────────────┘    └──────┬───────┘    └───────┬────────┘  │
│                             │                     │           │
│                      ┌──────▼───────┐    ┌───────▼────────┐  │
│                      │  Kafka       │    │  Tool          │  │
│                      │  Producer    │    │  Registry      │  │
│                      └──────┬───────┘    └───────┬────────┘  │
│                             │                     │           │
│                      ┌──────▼───────────────────▼────────┐  │
│                      │         Kafka Consumer              │  │
│                      │    (Ingestion Pipeline)             │  │
│                      │  PDFBox → Chunker → Embedder        │  │
│                      └──────────────┬──────────────────────┘  │
│                                     │                         │
│                      ┌──────────────▼──────────────────────┐  │
│                      │     Repository Layer (JPA)           │  │
│                      │  DocumentRepo / ChunkRepo /          │  │
│                      │  SessionRepo / TraceRepo             │  │
│                      └──────────────┬──────────────────────┘  │
└─────────────────────────────────────┼───────────────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
     ┌────────▼────────┐    ┌─────────▼──────────┐    ┌──────▼──────┐
     │  PostgreSQL 16   │    │  Apache Kafka       │    │  External   │
     │  + pgvector      │    │  (Docker)           │    │  APIs       │
     │                  │    │                     │    │  Gemini     │
     │  documents       │    │  findoc.ingestion   │    │  OpenRouter │
     │  document_chunks │    │  findoc.ingestion   │    └─────────────┘
     │  agent_sessions  │    │    .dlq             │
     │  session_messages│    └────────────────────┘
     │  query_traces    │
     └─────────────────┘
```

---

## 8. Key Service Implementations

### 8.1 ChunkingService

```java
@Service
public class ChunkingService {

    private static final int CHUNK_SIZE_TOKENS = 512;
    private static final int OVERLAP_TOKENS = 50;

    // Simple whitespace tokeniser for POC — replace with proper tokeniser for prod
    public List<String> chunk(String fullText) {
        String[] words = fullText.split("\\s+");
        List<String> chunks = new ArrayList<>();
        int step = CHUNK_SIZE_TOKENS - OVERLAP_TOKENS;
        for (int i = 0; i < words.length; i += step) {
            int end = Math.min(i + CHUNK_SIZE_TOKENS, words.length);
            chunks.add(String.join(" ", Arrays.copyOfRange(words, i, end)));
            if (end == words.length) break;
        }
        return chunks;
    }
}
```

### 8.2 EmbeddingService (Gemini)

```java
@Service
public class GeminiEmbeddingService {

    private static final String GEMINI_EMBED_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent";

    @Value("${gemini.api.key}")
    private String apiKey;

    private final RestClient restClient = RestClient.create();

    public float[] embed(String text) {
        var response = restClient.post()
            .uri(GEMINI_EMBED_URL + "?key=" + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of(
                "model", "models/text-embedding-004",
                "content", Map.of("parts", List.of(Map.of("text", text)))
            ))
            .retrieve()
            .body(GeminiEmbedResponse.class);

        return response.embedding().values();
    }
}
```

### 8.3 VectorSearchRepository

```java
@Repository
public class VectorSearchRepository {

    @PersistenceContext
    private EntityManager em;

    public List<ChunkSearchResult> findSimilar(
            float[] queryEmbedding,
            UUID tenantId,
            List<UUID> documentIds,
            int topK) {

        String vectorLiteral = toVectorLiteral(queryEmbedding);

        String docFilter = documentIds.isEmpty()
            ? ""
            : "AND document_id = ANY(:docIds)";

        String sql = """
            SELECT id, document_id, content, metadata,
                   1 - (embedding <=> CAST(:vec AS vector)) AS score
            FROM document_chunks
            WHERE tenant_id = :tenantId
              AND embedding IS NOT NULL
              %s
            ORDER BY embedding <=> CAST(:vec AS vector)
            LIMIT :topK
            """.formatted(docFilter);

        var query = em.createNativeQuery(sql, "ChunkSearchResultMapping")
            .setParameter("vec", vectorLiteral)
            .setParameter("tenantId", tenantId)
            .setParameter("topK", topK);

        if (!documentIds.isEmpty())
            query.setParameter("docIds", documentIds.toArray(UUID[]::new));

        return query.getResultList();
    }
}
```

### 8.4 AgentService (Core Loop)

```java
@Service
public class AgentService {

    private static final int MAX_ITERATIONS = 5;

    // Simplified agent loop — intent classify → tool calls → final answer
    public AgentResponse query(AgentQueryRequest request, UUID tenantId, UUID userId) {

        var traceSteps = new ArrayList<TraceStep>();
        var sessionId = resolveSession(request.sessionId(), tenantId, userId);

        // Step 1: classify intent
        var intent = classifyIntent(request.query());
        traceSteps.add(TraceStep.of("classify_intent", intent.name()));

        // Step 2: embed query
        float[] queryEmbedding = embeddingService.embed(request.query());

        // Step 3: agent loop
        List<Chunk> retrievedChunks = new ArrayList<>();
        String finalAnswer = null;
        int iteration = 0;

        while (iteration < MAX_ITERATIONS && finalAnswer == null) {
            var toolCall = llmService.decideNextTool(
                request.query(), intent, retrievedChunks, traceSteps);

            switch (toolCall.toolName()) {
                case "vector_search" -> {
                    var chunks = vectorRepo.findSimilar(
                        queryEmbedding, tenantId, request.documentIds(), 5);
                    retrievedChunks.addAll(chunks);
                    traceSteps.add(TraceStep.of("vector_search",
                        Map.of("chunksFound", chunks.size())));
                }
                case "generate_report" -> {
                    finalAnswer = llmService.generateAnswer(
                        request.query(), retrievedChunks,
                        sessionMessageRepo.findBySession(sessionId));
                    traceSteps.add(TraceStep.of("generate_report",
                        Map.of("answerLength", finalAnswer.length())));
                }
                // handle other tools...
            }
            iteration++;
        }

        // Step 4: persist trace and return
        var trace = traceRepo.save(QueryTrace.builder()
            .sessionId(sessionId).tenantId(tenantId)
            .query(request.query()).intent(intent.name())
            .steps(traceSteps).answer(finalAnswer)
            .build());

        sessionMessageRepo.save(new SessionMessage(sessionId, "user", request.query()));
        sessionMessageRepo.save(new SessionMessage(sessionId, "assistant", finalAnswer));

        return AgentResponse.builder()
            .queryId(trace.getId()).sessionId(sessionId)
            .answer(finalAnswer).sources(toSourceDTOs(retrievedChunks))
            .intent(intent.name()).stepsTaken(traceSteps)
            .build();
    }
}
```

---

## 9. Kafka Configuration

### Topics
| Topic | Partitions | Purpose |
|-------|-----------|---------|
| `findoc.ingestion` | 3 | Document ingestion jobs |
| `findoc.ingestion.dlq` | 1 | Dead-letter for failed jobs |

### Message Schema
```json
{
  "documentId": "3f7a1b2c-...",
  "tenantId": "00000000-...",
  "userId": "00000000-...",
  "filePath": "/tmp/uploads/3f7a1b2c.pdf",
  "fileType": "PDF",
  "attemptNumber": 1
}
```

### Consumer Error Handling
```java
@KafkaListener(topics = "findoc.ingestion", groupId = "findoc-ingestion-group")
public void consume(IngestionMessage message, Acknowledgment ack) {
    try {
        ingestionPipeline.process(message);
        ack.acknowledge();
    } catch (Exception e) {
        if (message.attemptNumber() >= 3) {
            kafkaTemplate.send("findoc.ingestion.dlq", message);
            ack.acknowledge();
        } else {
            // Nack to retry — Kafka will redeliver
            throw e;
        }
    }
}
```

---

## 10. Docker Compose

```yaml
# docker-compose.yml
version: '3.9'

services:

  postgres:
    image: pgvector/pgvector:pg16
    container_name: findoc-postgres
    environment:
      POSTGRES_DB: findoc
      POSTGRES_USER: findoc
      POSTGRES_PASSWORD: findoc_dev
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U findoc"]
      interval: 5s
      timeout: 5s
      retries: 10

  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    container_name: findoc-zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: findoc-kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true'
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 10s
      timeout: 10s
      retries: 10

volumes:
  pgdata:
```

---

## 11. Application Configuration

```yaml
# src/main/resources/application.yml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/findoc
    username: findoc
    password: findoc_dev
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate          # Liquibase owns schema, JPA validates
    show-sql: false
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: findoc-ingestion-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.findoc.*"
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 20MB

# External APIs (override with env vars or .env)
gemini:
  api:
    key: ${GEMINI_API_KEY}

openrouter:
  api:
    key: ${OPENROUTER_API_KEY}
    base-url: https://openrouter.ai/api/v1
    model: mistralai/mistral-7b-instruct

# JWT
jwt:
  secret: ${JWT_SECRET:local-dev-secret-change-in-prod-min-256-bits}
  expiry-seconds: 3600

# Ingestion
ingestion:
  upload-dir: /tmp/findoc-uploads
  max-retry: 3

# Chunking
chunking:
  size-tokens: 512
  overlap-tokens: 50
  top-k: 5

# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always

# Logging
logging:
  pattern:
    console: >
      {"timestamp":"%d{ISO8601}","level":"%p","traceId":"%X{traceId}",
       "tenantId":"%X{tenantId}","logger":"%logger{36}","message":"%m"}%n
```

---

## 12. Gradle `build.gradle` — Key Dependencies

The generated project uses the Gradle Groovy DSL with the Spring Boot plugin and
the dependency-management plugin. The dependency coordinates below are declared
in `build.gradle`; the Gradle wrapper pins the build to a compatible Gradle 8.x
release.

```groovy
dependencies {

  // Spring Boot
  implementation 'org.springframework.boot:spring-boot-starter-web'
  implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
  implementation 'org.springframework.boot:spring-boot-starter-security'
  implementation 'org.springframework.boot:spring-boot-starter-actuator'
  implementation 'org.springframework.boot:spring-boot-starter-validation'

  // Kafka
  implementation 'org.springframework.kafka:spring-kafka'

  // PostgreSQL
  runtimeOnly 'org.postgresql:postgresql'

  // Liquibase
  implementation 'org.liquibase:liquibase-core'

  // PDF parsing
  implementation 'org.apache.pdfbox:pdfbox:3.0.2'

  // JWT
  implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
  runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
  runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'

  // OpenAPI / Swagger
  implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'

  // Lombok
  compileOnly 'org.projectlombok:lombok'
  annotationProcessor 'org.projectlombok:lombok'

  // Testing
  testImplementation 'org.springframework.boot:spring-boot-starter-test'
  testImplementation 'org.springframework.kafka:spring-kafka-test'
  testImplementation 'org.springframework.security:spring-security-test'

}
```

---

## 13. Project Structure

```
findoc-agent/
├── docker-compose.yml
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
├── gradle/wrapper/gradle-wrapper.properties
├── README.md
├── docs/
│   └── architecture.mmd          # Mermaid diagram
├── scripts/
│   └── sample-requests.sh        # curl demo script
└── src/
    ├── main/
    │   ├── java/com/findoc/
    │   │   ├── FindocAgentApplication.java
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java
    │   │   │   ├── KafkaConfig.java
    │   │   │   └── OpenApiConfig.java
    │   │   ├── controller/
    │   │   │   ├── AuthController.java
    │   │   │   ├── DocumentController.java
    │   │   │   └── AgentController.java
    │   │   ├── service/
    │   │   │   ├── auth/
    │   │   │   │   ├── JwtService.java
    │   │   │   │   └── AuthService.java
    │   │   │   ├── document/
    │   │   │   │   ├── DocumentService.java
    │   │   │   │   ├── ChunkingService.java
    │   │   │   │   ├── IngestionPipeline.java
    │   │   │   │   └── PdfExtractionService.java
    │   │   │   ├── embedding/
    │   │   │   │   └── GeminiEmbeddingService.java
    │   │   │   └── agent/
    │   │   │       ├── AgentService.java
    │   │   │       ├── IntentClassifier.java
    │   │   │       ├── LlmService.java        # OpenRouter calls
    │   │   │       └── tools/
    │   │   │           ├── ToolRegistry.java
    │   │   │           ├── VectorSearchTool.java
    │   │   │           ├── CompareDocumentsTool.java
    │   │   │           └── GenerateReportTool.java
    │   │   ├── messaging/
    │   │   │   ├── IngestionProducer.java
    │   │   │   └── IngestionConsumer.java
    │   │   ├── repository/
    │   │   │   ├── DocumentRepository.java
    │   │   │   ├── ChunkRepository.java
    │   │   │   ├── VectorSearchRepository.java  # native SQL
    │   │   │   ├── AgentSessionRepository.java
    │   │   │   ├── SessionMessageRepository.java
    │   │   │   └── QueryTraceRepository.java
    │   │   ├── entity/
    │   │   │   ├── Document.java
    │   │   │   ├── DocumentChunk.java
    │   │   │   ├── AgentSession.java
    │   │   │   ├── SessionMessage.java
    │   │   │   └── QueryTrace.java
    │   │   ├── dto/
    │   │   │   ├── request/
    │   │   │   └── response/
    │   │   ├── exception/
    │   │   │   ├── GlobalExceptionHandler.java
    │   │   │   └── FindocException.java
    │   │   └── util/
    │   │       └── TenantContext.java          # ThreadLocal tenant ID
    │   └── resources/
    │       ├── application.yml
    │       └── db/changelog/
    │           ├── db.changelog-master.xml
    │           └── migrations/
    │               └── 001-init.sql
    └── test/
        └── java/com/findoc/
            ├── service/
            │   ├── ChunkingServiceTest.java
            │   ├── AgentServiceTest.java
            │   └── DocumentServiceTest.java
            └── controller/
                ├── DocumentControllerTest.java
                └── AgentControllerTest.java
```

---

## 14. Sample curl Script (for README demo)

```bash
#!/bin/bash
# scripts/sample-requests.sh
BASE="http://localhost:8080/api/v1"

echo "=== 1. Get JWT token ==="
TOKEN=$(curl -s -X POST "$BASE/auth/token" \
  -H "Content-Type: application/json" \
  -d '{"username":"demo@findoc.local","password":"demo123"}' \
  | jq -r '.accessToken')
echo "Token: ${TOKEN:0:40}..."

echo ""
echo "=== 2. Upload a PDF ==="
DOC_RESP=$(curl -s -X POST "$BASE/documents/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./sample-loan-agreement.pdf")
DOC_ID=$(echo $DOC_RESP | jq -r '.documentId')
echo "Document ID: $DOC_ID"

echo ""
echo "=== 3. Poll ingestion status ==="
sleep 10
curl -s "$BASE/documents/$DOC_ID/status" \
  -H "Authorization: Bearer $TOKEN" | jq .

echo ""
echo "=== 4. Query the agent ==="
QUERY_RESP=$(curl -s -X POST "$BASE/agent/query" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"query\": \"What are the penalty clauses in this document?\",
    \"documentIds\": [\"$DOC_ID\"]
  }")
echo $QUERY_RESP | jq '{answer, intent, confidence, stepsTaken}'
QUERY_ID=$(echo $QUERY_RESP | jq -r '.queryId')

echo ""
echo "=== 5. Explain the agent trace ==="
curl -s "$BASE/agent/explain/$QUERY_ID" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## 15. Environment Variables (.env for local dev)

```bash
# .env — never commit this file
GEMINI_API_KEY=your_gemini_api_key_here
OPENROUTER_API_KEY=your_openrouter_api_key_here
JWT_SECRET=your-local-dev-secret-at-least-32-characters-long
```

---

## 16. Getting Started (Local Dev)

```bash
# 1. Clone and set environment
cp .env.example .env
# Fill in GEMINI_API_KEY and OPENROUTER_API_KEY

# 2. Start infrastructure
docker compose up -d
# Wait ~20 seconds for Kafka and PostgreSQL to be healthy

# 3. Run the application
./gradlew bootRun

# 4. Open Swagger UI
open http://localhost:8080/swagger-ui.html

# 5. Run sample demo
chmod +x scripts/sample-requests.sh
./scripts/sample-requests.sh

# Build and test without starting the application
./gradlew test
./gradlew build
```

---

## 17. What to Highlight in the README (for Singapore Employers)

1. **Multi-tenant data isolation** — every vector search is scoped by tenant_id; link to your Wissen migration experience
2. **Async ingestion via Kafka** — upload returns in <200ms; processing is decoupled with DLQ for resilience
3. **Agentic loop, not plain RAG** — intent classification + tool-calling loop with a capped iteration guard
4. **Full auditability** — /explain endpoint returns every agent step and score; critical for fintech compliance
5. **Production patterns** — circuit breakers on external APIs, structured logging with trace IDs, Liquibase migrations, Actuator health checks
6. **Zero cost** — entire stack runs locally; Gemini free tier + OpenRouter free credits

---

*Spec version: 1.0 | Prepared for FinDoc Agent POC | August 2026*
