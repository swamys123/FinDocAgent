package com.findoc.repository;

import com.findoc.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
    @Modifying
    @Query("update DocumentChunk c set c.deletedAt = :deletedAt where c.document.id = :documentId and c.tenant.id = :tenantId and c.deletedAt is null")
    void softDeleteByDocumentIdAndTenantId(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId, @Param("deletedAt") Instant deletedAt);

    @Modifying
    @Query("update DocumentChunk c set c.deletedAt = :deletedAt where c.document.id = :documentId and c.tenant.id = :tenantId and c.chunkIndex >= :firstRemovedIndex and c.deletedAt is null")
    void softDeleteFromChunkIndex(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId,
                                  @Param("firstRemovedIndex") int firstRemovedIndex, @Param("deletedAt") Instant deletedAt);

    @Modifying(flushAutomatically = true)
    @Query(value = """
        insert into document_chunks (document_id, tenant_id, chunk_index, content, token_count, embedding)
        select d.id, d.tenant_id, :chunkIndex, :content, :tokenCount, cast(:embedding as vector)
        from documents d
        where d.id = :documentId and d.tenant_id = :tenantId and d.deleted_at is null
        on conflict (document_id, chunk_index) where deleted_at is null
        do update set content = excluded.content,
                      token_count = excluded.token_count,
                      embedding = excluded.embedding
        where document_chunks.tenant_id = excluded.tenant_id
        """, nativeQuery = true)
    int upsertLiveChunk(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId,
                        @Param("chunkIndex") int chunkIndex, @Param("content") String content,
                        @Param("tokenCount") int tokenCount, @Param("embedding") String embedding);

    @Query("select count(c) from DocumentChunk c where c.document.id = :documentId and c.tenant.id = :tenantId and c.deletedAt is null")
    long countByDocumentIdAndTenantId(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId);

    @Query("select c from DocumentChunk c where c.document.id = :documentId and c.tenant.id = :tenantId and c.deletedAt is null order by c.chunkIndex asc")
    List<DocumentChunk> findByDocumentIdAndTenantIdOrderByChunkIndexAsc(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId);

        @Query(value = """
                select c.* from document_chunks c
                join documents d on d.id = c.document_id
                where c.tenant_id = :tenantId
                    and d.tenant_id = :tenantId
                    and d.deleted_at is null
                    and c.deleted_at is null
                    and c.embedding is not null
                order by c.embedding <=> cast(:embedding as vector)
                limit :limit
                """, nativeQuery = true)
            List<DocumentChunk> searchSimilar(@Param("embedding") float[] embedding,
                                                                            @Param("tenantId") UUID tenantId,
                                                                            @Param("limit") int limit);

        @Query(value = """
                select c.* from document_chunks c
                join documents d on d.id = c.document_id
                where c.tenant_id = :tenantId
                    and c.document_id in (:documentIds)
                    and d.tenant_id = :tenantId
                    and d.deleted_at is null
                    and c.deleted_at is null
                    and c.embedding is not null
                order by c.embedding <=> cast(:embedding as vector)
                limit :limit
                """, nativeQuery = true)
            List<DocumentChunk> searchSimilarInDocuments(@Param("embedding") float[] embedding,
                                                                                                 @Param("tenantId") UUID tenantId,
                                                                                                 @Param("documentIds") List<UUID> documentIds,
                                                                                                 @Param("limit") int limit);
}
