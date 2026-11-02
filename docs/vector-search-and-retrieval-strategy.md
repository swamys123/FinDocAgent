# Vector Search Architecture & Retrieval Strategy

This document outlines the evaluation of PostgreSQL with `pgvector` for production vector search, compares it with standalone vector databases (such as Chroma, Qdrant, and Pinecone), and details the vector retrieval strategy implemented in **FinDoc Agent**.

---

## 1. Vector Search Evaluation: PostgreSQL + `pgvector` in Production

### Is PostgreSQL + `pgvector` Good for Production?
**Yes.** PostgreSQL with the `pgvector` extension is widely adopted and highly performant in enterprise production environments.

#### Key Advantages:
* **Unified Database & Single Source of Truth:** Relational data (users, tenants, document metadata, audit traces) and vector embeddings reside in the exact same database. This eliminates dual-write synchronization and consistency issues between a primary database and an external vector engine.
* **ACID Compliance & Tenant-Scoped Queries:** PostgreSQL executes metadata filters (`tenant_id = :tenantId AND deleted_at IS NULL AND document_id IN (...)`) in the same query as vector ranking. FinDoc Agent's retrieval queries constrain both chunks and their parent documents to the request tenant.
* **Operational Simplicity:** Leverage existing Postgres infrastructure for backups, high availability, point-in-time recovery, Liquibase migrations, and monitoring rather than managing a separate vector database cluster.
* **Scalability & Indexing:** `pgvector` supports `HNSW` and `IVFFlat` indexes. Search latency and useful index settings depend on data size, filters, hardware, and query shape; no production latency benchmark is documented for this project.

### Comparison: `pgvector` vs. Chroma vs. Dedicated Vector DBs

| Feature / Criteria | PostgreSQL (`pgvector`) | Chroma DB | Dedicated Vector DBs (Qdrant, Pinecone, Milvus) |
| :--- | :--- | :--- | :--- |
| **Primary Focus** | Relational DB + Vector Extension | Light-weight AI prototyping | Pure large-scale vector search |
| **Data Consistency** | Full ACID transactions | Eventual / File-based | Varies (mostly eventual/KV) |
| **Multi-Tenancy & Metadata** | Built-in via SQL (`WHERE tenant_id = ...`) | Basic metadata dict filtering | Metadata payload filtering |
| **Ecosystem & Scaffolding** | Java / Spring Data JPA, SQL, Liquibase | Python / JS SDKs | REST / gRPC SDKs |
| **Best Suited For** | Enterprise production systems, multi-tenant SaaS | Prototypes, Python RAG pipelines, local experiments | Massive scale (100M+ to billions of vectors) |

---

## 2. Retrieval Strategy in FinDoc Agent

FinDoc Agent implements a **tenant-isolated dense vector retrieval pipeline** integrated with conversational context and agentic reasoning.

```mermaid
flowchart TD
    UserQuery[User Query] --> Embedder[Embedding Service\nGemini 768-d]
    Embedder --> VectorQuery[Native Cosine Search\npgvector <=> operator]
    VectorQuery --> DB[(PostgreSQL\ndocument_chunks)]
    DB --> FilteredChunks[Top-K Chunks\nFiltered by Tenant, Document Scope & Soft-Delete]
    FilteredChunks --> Prompt[Prompt Context + Intent + Session History]
    Prompt --> LLM[OpenRouter / LLM Generation]
    LLM --> Response[Agent Response]
```

### Retrieval Pipeline Details

1. **Document Ingestion & Chunking**
   * `ChunkingService` splits extracted text on whitespace into chunks of up to **512 words**, with a **50-word overlap**. It is not a model-aware tokenizer, so this is not a strict 512-token limit.
   * The persisted `token_count` currently counts whitespace-delimited words. PDF page counts are persisted on successful ingestion, but page numbers are not attached to individual chunks.
   * Embeddings are generated using Google Gemini (`gemini-embedding-001`), producing **768-dimensional dense vectors** (`com.findoc.service.embedding.GeminiEmbeddingService`).
   * Ingestion is driven by the durable outbox and stages each chunk's content, count, and embedding in `ingestion_chunk_embeddings`. Retries reuse a staged embedding when its chunk content is unchanged; successful completion upserts the live chunks and records the extracted page count.

2. **Database Indexing & Distance Metric**
   * **Distance Metric:** Cosine Distance using `pgvector`'s `<=>` operator (smaller distance value indicates higher semantic similarity).
   * **Index Type:** An `IVFFlat` vector index (`idx_document_chunks_embedding_cosine`) configured with `lists = 100` defined in `findoc-agent/src/main/resources/db/changelog/db.changelog-002-vector.xml`.

3. **Tenant-Scoped Native Vector Search**
   * The query is embedded with the same Gemini embedding service, then vector retrieval is executed in `com.findoc.repository.DocumentChunkRepository` via parameterized native SQL queries. Both chunks and parent documents are filtered by `tenant_id`; deleted documents and chunks and chunks without embeddings are excluded.
   * The result limit is configurable through `agent.top-k` and defaults to **5**. When `documentIds` are supplied in `AgentQueryRequest`, retrieval is restricted to that document subset.
   * **All tenant documents:**
     ```sql
     SELECT c.* FROM document_chunks c
     JOIN documents d ON d.id = c.document_id
     WHERE c.tenant_id = :tenantId
       AND d.tenant_id = :tenantId
       AND d.deleted_at IS NULL
       AND c.deleted_at IS NULL
       AND c.embedding IS NOT NULL
     ORDER BY c.embedding <=> CAST(:embedding AS vector)
     LIMIT :limit
     ```
   * **Scoped document subset:** `searchSimilarInDocuments` adds `c.document_id IN (:documentIds)` to the same tenant, soft-delete, and embedding filters.

4. **Intent Classification & Context Augmentation**
   * Query intent is classified (`LOOKUP`, `COMPARE`, `SUMMARISE`, `REPORT`) via `com.findoc.service.agent.IntentClassifier`.
   * The top-K matched chunk contents (default `K=5`) are fetched along with up to 10 recent chat messages from the active session (`SessionMessageRepository`).
   * The combined grounded context and conversation history are passed to `OpenRouterGenerationService` to compose the final structured agent response.

### Operational note

The configured `IVFFlat` index and tenant/document filters describe the implemented query shape, not a measured latency guarantee. Production performance should be evaluated with representative tenant sizes and filters before making latency or scale commitments.
