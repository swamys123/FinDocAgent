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
