package com.findoc.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class IngestionProgressRepository {
    private final JdbcTemplate jdbcTemplate;

    public IngestionProgressRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StagedChunk> findChunks(UUID documentId, UUID tenantId) {
        return jdbcTemplate.query("""
            select chunk_index, content, token_count, embedding::text
            from ingestion_chunk_embeddings
            where document_id = ? and tenant_id = ?
            order by chunk_index
            """, (resultSet, row) -> new StagedChunk(
                resultSet.getInt("chunk_index"),
                resultSet.getString("content"),
                resultSet.getInt("token_count"),
                resultSet.getString("embedding")), documentId, tenantId);
    }

    public int saveChunk(UUID documentId, UUID tenantId, int chunkIndex, String content,
                         int tokenCount, String embedding) {
        int touched = jdbcTemplate.update("""
            update documents set updated_at = current_timestamp
            where id = ? and tenant_id = ? and deleted_at is null and status = 'PROCESSING'
            """, documentId, tenantId);
        if (touched != 1) {
            return 0;
        }
        return jdbcTemplate.update("""
            insert into ingestion_chunk_embeddings
                (document_id, tenant_id, chunk_index, content, token_count, embedding)
            select d.id, d.tenant_id, ?, ?, ?, cast(? as vector)
            from documents d
            where d.id = ? and d.tenant_id = ? and d.deleted_at is null and d.status = 'PROCESSING'
            on conflict (document_id, tenant_id, chunk_index)
            do update set content = excluded.content, token_count = excluded.token_count,
                          embedding = excluded.embedding, updated_at = current_timestamp
            """, chunkIndex, content, tokenCount, embedding, documentId, tenantId);
    }

    public int deleteChunks(UUID documentId, UUID tenantId) {
        return jdbcTemplate.update(
            "delete from ingestion_chunk_embeddings where document_id = ? and tenant_id = ?",
            documentId, tenantId);
    }

    public record StagedChunk(int chunkIndex, String content, int tokenCount, String embedding) {}
}
