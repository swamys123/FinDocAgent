#!/bin/bash
# setup-findoc.sh - generates the FinDoc Agent Gradle project
# Usage: chmod +x setup.sh && ./setup.sh

set -e

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

rootProject.name = 'findoc-agent'
EOF

cat > build.gradle << 'EOF'
plugins {
  id 'java'
  id 'org.springframework.boot' version '3.2.12'
  id 'io.spring.dependency-management' version '1.1.6'
}

group = 'com.findoc'
version = '1.0.0'

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

configurations {
  compileOnly {
    extendsFrom annotationProcessor
  }
}

dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-web'
  implementation 'org.springframework.boot:spring-boot-starter-validation'
  implementation 'org.springframework.boot:spring-boot-starter-security'
  implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
  implementation 'org.springframework.boot:spring-boot-starter-actuator'
  implementation 'org.springframework.kafka:spring-kafka'
  implementation 'org.liquibase:liquibase-core'
  implementation 'org.apache.pdfbox:pdfbox:3.0.2'
  implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'
  implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
  runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
  runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'
  runtimeOnly 'org.postgresql:postgresql'

  compileOnly 'org.projectlombok:lombok'
  annotationProcessor 'org.projectlombok:lombok'
  annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'

  testImplementation 'org.springframework.boot:spring-boot-starter-test'
  testImplementation 'org.springframework.kafka:spring-kafka-test'
  testImplementation 'org.springframework.security:spring-security-test'
  testRuntimeOnly 'com.h2database:h2'
}

tasks.named('test') {
  useJUnitPlatform()
}

tasks.named('bootBuildImage') {
  imageName = "findoc-agent:${project.version}"
}
EOF

mkdir -p gradle/wrapper
cat > gradle/wrapper/gradle-wrapper.properties << 'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

cat > gradlew << 'EOF'
#!/bin/sh
set -eu

GRADLE_VERSION=8.10.2
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-$GRADLE_VERSION-bin"
GRADLE_BIN="$GRADLE_HOME/gradle-$GRADLE_VERSION/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  archive="$GRADLE_HOME/gradle-$GRADLE_VERSION-bin.zip"
  mkdir -p "$GRADLE_HOME"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$archive" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$archive" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  else
    echo "curl or wget is required to download Gradle" >&2
    exit 1
  fi
  unzip -q "$archive" -d "$GRADLE_HOME"
fi

exec "$GRADLE_BIN" "$@"
EOF
chmod +x gradlew

cat > gradlew.bat << 'EOF'
@echo off
setlocal
set GRADLE_VERSION=8.10.2
set GRADLE_HOME=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin
set GRADLE_BIN=%GRADLE_HOME%\gradle-%GRADLE_VERSION%\bin\gradle.bat
if not exist "%GRADLE_BIN%" (
  echo Gradle is not installed. Run this project on Windows with Gradle 8.10.2 available on PATH.
  exit /b 1
)
call "%GRADLE_BIN%" %*
EOF

cat > README.md << 'EOF'
# FinDoc Agent

Agentic document intelligence backend built with Java 17 and Spring Boot 3.2.

## Run locally

```bash
cp .env.example .env
docker compose up -d
./gradlew bootRun
```

Run checks with `./gradlew test` or `./gradlew build`. Swagger UI is available at
`http://localhost:8080/swagger-ui.html` when the application is running.

The generated project contains the package structure, infrastructure configuration,
database migration, and service/controller extension points described in the POC
specification. External API keys and the JWT secret must be supplied through the
environment; do not commit `.env`.
EOF

# ── Source directories ────────────────────────────────────────────────
JAVA="src/main/java/$BASE"
RES="src/main/resources"
TEST_JAVA="src/test/java/$BASE"
TEST_RES="src/test/resources"

mkdir -p \
  $JAVA/config \
  $JAVA/controller \
  $JAVA/service/auth \
  $JAVA/service/document \
  $JAVA/service/embedding \
  $JAVA/service/agent \
  $JAVA/service/agent/tools \
  $JAVA/messaging \
  $JAVA/repository \
  $JAVA/entity \
  $JAVA/dto/request \
  $JAVA/dto/response \
  $JAVA/exception \
  $JAVA/util \
  $JAVA/security \
  $RES/db/changelog/migrations \
  $TEST_JAVA/controller \
  $TEST_JAVA/service \
  $TEST_JAVA/repository \
  $TEST_RES \
  docs \
  scripts

cat > "$TEST_JAVA/FindocAgentApplicationTests.java" << 'EOF'
package com.findoc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FindocAgentApplicationTests {

  @Test
  void contextLoads() {
  }
}
EOF

# ── .gitignore ────────────────────────────────────────────────────────
cat > .gitignore << 'EOF'
# Gradle
.gradle/
build/
target/
*.class
!gradle/wrapper/gradle-wrapper.jar

# IDE
.idea/
*.iml
.vscode/
*.swp

# Environment — NEVER commit
.env
*.env

# Logs
*.log
logs/

# OS
.DS_Store
Thumbs.db

# Uploads (local dev only)
/tmp/findoc-uploads/
EOF

# ── .env.example ─────────────────────────────────────────────────────
cat > .env.example << 'EOF'
# Copy this to .env and fill in real values — never commit .env
GEMINI_API_KEY=your_gemini_api_key_here
OPENROUTER_API_KEY=your_openrouter_api_key_here
JWT_SECRET=local-dev-secret-at-least-32-characters-long-change-in-prod
POSTGRES_PASSWORD=findoc_dev
EOF

# ── Docker Compose ────────────────────────────────────────────────────
cat > docker-compose.yml << 'EOF'
version: '3.9'

services:

  postgres:
    image: pgvector/pgvector:pg16
    container_name: findoc-postgres
    environment:
      POSTGRES_DB: findoc
      POSTGRES_USER: findoc
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-findoc_dev}
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
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: findoc-kafka
    depends_on:
      zookeeper:
        condition: service_started
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true'
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 10s
      timeout: 10s
      retries: 10

volumes:
  pgdata:
EOF

# ── application.yml ───────────────────────────────────────────────────
cat > $RES/application.yml << 'EOF'
server:
  port: 8080
  error:
    include-message: always

spring:
  application:
    name: findoc-agent
  datasource:
    url: jdbc:postgresql://localhost:5432/findoc
    username: findoc
    password: ${POSTGRES_PASSWORD:findoc_dev}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    open-in-view: false
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

gemini:
  api:
    key: ${GEMINI_API_KEY}
    base-url: https://generativelanguage.googleapis.com/v1beta
    model: text-embedding-004
    embedding-dimension: 768

openrouter:
  api:
    key: ${OPENROUTER_API_KEY}
    base-url: https://openrouter.ai/api/v1
    model: mistralai/mistral-7b-instruct
    timeout-seconds: 30

jwt:
  secret: ${JWT_SECRET:local-dev-secret-change-in-prod-min-256-bits}
  expiry-seconds: 3600

ingestion:
  upload-dir: /tmp/findoc-uploads
  max-retry: 3
  kafka:
    topic: findoc.ingestion
    dlq-topic: findoc.ingestion.dlq

chunking:
  size-tokens: 512
  overlap-tokens: 50

agent:
  top-k: 5
  max-iterations: 5

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,loggers
  endpoint:
    health:
      show-details: always
  info:
    env:
      enabled: true

info:
  app:
    name: FinDoc Agent
    description: Agentic Document Intelligence API
    version: 1.0.0

logging:
  level:
    com.findoc: DEBUG
    org.springframework.kafka: INFO
    org.hibernate.SQL: DEBUG
EOF

# ── application-test.yml ─────────────────────────────────────────────
cat > $TEST_RES/application-test.yml << 'EOF'
spring:
  datasource:
    url: jdbc:h2:mem:findoc_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    database-platform: org.hibernate.dialect.H2Dialect
  liquibase:
    enabled: false
  kafka:
    bootstrap-servers: ${spring.embedded.kafka.brokers}

gemini:
  api:
    key: test-key
openrouter:
  api:
    key: test-key
jwt:
  secret: test-secret-at-least-32-characters-long
EOF

# ── Liquibase master changelog ────────────────────────────────────────
cat > $RES/db/changelog/db.changelog-master.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>

--liquibase formatted sql

--changeset findoc:001-extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

--changeset findoc:002-tenants
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset findoc:003-users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset findoc:004-documents
CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id),
    filename VARCHAR(500) NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    page_count INTEGER,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_msg TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

--changeset findoc:005-document-chunks
CREATE TABLE document_chunks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id UUID NOT NULL REFERENCES documents(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER,
    embedding vector(768),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chunks_tenant_doc
    ON document_chunks (tenant_id, document_id);

CREATE INDEX idx_chunks_embedding
    ON document_chunks
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

--changeset findoc:006-sessions
CREATE TABLE agent_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE session_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES agent_sessions(id),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset findoc:007-traces
CREATE TABLE query_traces (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES agent_sessions(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    query TEXT NOT NULL,
    intent VARCHAR(50),
    steps JSONB,
    answer TEXT,
    confidence DECIMAL(4,3),
    duration_ms INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset findoc:008-seed-data
INSERT INTO tenants (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'demo-tenant');

INSERT INTO users (id, tenant_id, username, password, role) VALUES
    ('00000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000001',
     'demo@findoc.local',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCi.sMKMTxGiRm3/zI/XtGi',
     'USER');
-- password above is: demo123
EOF

# ── Main application class ────────────────────────────────────────────
cat > $JAVA/FindocAgentApplication.java << 'EOF'
package com.findoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableKafka
@EnableAsync
@ConfigurationPropertiesScan
public class FindocAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(FindocAgentApplication.class, args);
    }
}
EOF

# ── Config placeholder classes (Copilot fills these in) ──────────────
for cls in SecurityConfig KafkaConfig OpenApiConfig AsyncConfig; do
cat > $JAVA/config/${cls}.java << EOF
package com.findoc.config;

import org.springframework.context.annotation.Configuration;

// TODO: Copilot prompt — implement $cls for Spring Boot 3.x findoc-agent project
@Configuration
public class ${cls} {
}
EOF
done

# ── Controller stubs ──────────────────────────────────────────────────
for ctrl in AuthController DocumentController AgentController; do
cat > $JAVA/controller/${ctrl}.java << EOF
package com.findoc.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO: Copilot prompt — implement $ctrl REST endpoints per findoc-agent-spec.md
@RestController
@RequestMapping("/api/v1")
public class ${ctrl} {
}
EOF
done

# ── Service stubs ─────────────────────────────────────────────────────
declare -A SERVICE_MAP=(
  ["service/auth/JwtService"]="auth"
  ["service/auth/AuthService"]="auth"
  ["service/document/DocumentService"]="document"
  ["service/document/ChunkingService"]="document"
  ["service/document/IngestionPipeline"]="document"
  ["service/document/PdfExtractionService"]="document"
  ["service/embedding/GeminiEmbeddingService"]="embedding"
  ["service/agent/AgentService"]="agent"
  ["service/agent/IntentClassifier"]="agent"
  ["service/agent/LlmService"]="agent"
  ["service/agent/tools/ToolRegistry"]="agent.tools"
  ["service/agent/tools/VectorSearchTool"]="agent.tools"
  ["service/agent/tools/CompareDocumentsTool"]="agent.tools"
  ["service/agent/tools/GenerateReportTool"]="agent.tools"
)

for path in "${!SERVICE_MAP[@]}"; do
  pkg="${SERVICE_MAP[$path]}"
  cls=$(basename $path)
  dir=$(dirname $path)
  cat > $JAVA/${path}.java << EOF
package com.findoc.${pkg/\//.};

import org.springframework.stereotype.Service;

// TODO: Copilot prompt — implement $cls per findoc-agent-spec.md
@Service
public class ${cls} {
}
EOF
done

# ── Messaging stubs ───────────────────────────────────────────────────
for cls in IngestionProducer IngestionConsumer; do
cat > $JAVA/messaging/${cls}.java << EOF
package com.findoc.messaging;

import org.springframework.stereotype.Component;

// TODO: Copilot prompt — implement $cls using Spring Kafka per findoc-agent-spec.md
@Component
public class ${cls} {
}
EOF
done

# ── Exception handler ─────────────────────────────────────────────────
cat > $JAVA/exception/GlobalExceptionHandler.java << 'EOF'
package com.findoc.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FindocException.class)
    public ProblemDetail handleFindocException(FindocException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        detail.setType(URI.create("https://findoc.local/errors/" + ex.getErrorCode()));
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        detail.setType(URI.create("https://findoc.local/errors/internal"));
        return detail;
    }
}
EOF

cat > $JAVA/exception/FindocException.java << 'EOF'
package com.findoc.exception;

import org.springframework.http.HttpStatus;
import lombok.Getter;

@Getter
public class FindocException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public FindocException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static FindocException notFound(String resource) {
        return new FindocException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            resource + " not found");
    }

    public static FindocException forbidden() {
        return new FindocException(HttpStatus.FORBIDDEN, "FORBIDDEN",
            "Access denied");
    }

    public static FindocException badRequest(String message) {
        return new FindocException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static FindocException conflict(String message) {
        return new FindocException(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
EOF

# ── TenantContext utility ─────────────────────────────────────────────
cat > $JAVA/util/TenantContext.java << 'EOF'
package com.findoc.util;

import java.util.UUID;

/**
 * ThreadLocal holder for the authenticated tenant ID.
 * Set in the JWT security filter, cleared after request completes.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        TENANT.set(tenantId);
    }

    public static UUID get() {
        UUID id = TENANT.get();
        if (id == null) throw new IllegalStateException("No tenant in context");
        return id;
    }

    public static void clear() {
        TENANT.remove();
    }
}
EOF

# ── Sample curl script ────────────────────────────────────────────────
cat > scripts/demo.sh << 'EOF'
#!/bin/bash
# FinDoc Agent — end-to-end demo
# Usage: ./scripts/demo.sh [path-to-pdf]
set -e

BASE="http://localhost:8080/api/v1"
PDF="${1:-./docs/sample.pdf}"

echo "══════════════════════════════════════"
echo " FinDoc Agent — Demo Script"
echo "══════════════════════════════════════"

echo ""
echo "▶ 1. Authenticate"
TOKEN=$(curl -sf -X POST "$BASE/auth/token" \
  -H "Content-Type: application/json" \
  -d '{"username":"demo@findoc.local","password":"demo123"}' \
  | jq -r '.accessToken')
echo " ✓ Token obtained"

echo ""
echo "▶ 2. Upload document"
DOC_RESP=$(curl -sf -X POST "$BASE/documents/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$PDF")
DOC_ID=$(echo $DOC_RESP | jq -r '.documentId')
echo " ✓ Document ID: $DOC_ID"

echo ""
echo "▶ 3. Waiting for ingestion (polling every 3s)..."
for i in {1..20}; do
  STATUS=$(curl -sf "$BASE/documents/$DOC_ID/status" \
    -H "Authorization: Bearer $TOKEN" | jq -r '.status')
  echo " Status: $STATUS"
  if [[ "$STATUS" == "READY" ]]; then break; fi
  if [[ "$STATUS" == "FAILED" ]]; then echo "❌ Ingestion failed"; exit 1; fi
  sleep 3
done

echo ""
echo "▶ 4. Query the agent"
QUERY_RESP=$(curl -sf -X POST "$BASE/agent/query" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"Summarise the key points of this document\",\"documentIds\":[\"$DOC_ID\"]}")
QUERY_ID=$(echo $QUERY_RESP | jq -r '.queryId')

echo ""
echo "══ Answer ══"
echo $QUERY_RESP | jq -r '.answer'
echo ""
echo "══ Agent Steps ══"
echo $QUERY_RESP | jq '.stepsTaken'

echo ""
echo "▶ 5. Inspect agent trace"
curl -sf "$BASE/agent/explain/$QUERY_ID" \
  -H "Authorization: Bearer $TOKEN" | jq .

echo ""
echo "✅ Demo complete"
EOF
chmod +x scripts/demo.sh

# ── Architecture diagram (Mermaid) ────────────────────────────────────
cat > docs/architecture.mmd << 'EOF'
graph TB
    Client -->|JWT| API[Spring Boot API]
    API --> DC[DocumentController]
    API --> AC[AgentController]
    API --> AuC[AuthController]

    DC --> DS[DocumentService]
    DS --> KP[Kafka Producer]
    KP -->|findoc.ingestion| KC[Kafka Consumer]
    KC --> PDF[PdfExtractionService]
    KC --> CS[ChunkingService]
    KC --> ES[GeminiEmbeddingService]
    ES -->|vector\n768-dim| PG[(PostgreSQL\n+ pgvector)]

    AC --> AS[AgentService]
    AS --> IC[IntentClassifier]
    AS --> TR[ToolRegistry]
    TR --> VST[VectorSearchTool]
    TR --> CDT[CompareDocumentsTool]
    TR --> GRT[GenerateReportTool]
    VST -->|cosine similarity| PG
    GRT -->|prompt + context| OR[OpenRouter\nMistral-7B]
    AS --> QT[(query_traces)]
    AS --> SM[(session_messages)]

    ES -->|embeddings API| Gemini[Google Gemini\ntext-embedding-004]
EOF

# ── Copilot instructions ──────────────────────────────────────────────
mkdir -p .github
cat > .github/copilot-instructions.md << 'EOF'
# Copilot Instructions — FinDoc Agent

## Project
Agentic RAG backend. Java 17, Spring Boot 3.2.x, PostgreSQL 16 + pgvector,
Apache Kafka, Gemini embeddings, OpenRouter LLM (Mistral-7B).

## Package root
com.findoc

## Non-negotiable rules
- All JPA entities use UUID primary keys generated by uuid_generate_v4()
- All repository queries MUST include tenant_id filter — never return cross-tenant data
- Use Java 17 records for DTOs and request/response objects
- Use RestClient, not WebClient or RestTemplate, for HTTP calls
- Vector similarity uses the cosine distance operator <=>, not L2 distance <->
- Kafka consumers must check retry count before sending to findoc.ingestion.dlq
- JWT must carry tenant_id and user_id claims; always extract both in filters
- Chunk size: 512 tokens, overlap: 50 tokens
- Agent loop maximum: 5 iterations; never remove this guard
- Log trace_id and tenant_id in MDC on every request

## Package structure
com.findoc.config, .controller, .service.auth, .service.document,
.service.embedding, .service.agent, .service.agent.tools,
.messaging, .repository, .entity, .dto.request, .dto.response,
.exception, .util
EOF

echo "Created $PROJECT"
echo "Next steps: cd $PROJECT && ./gradlew test"
