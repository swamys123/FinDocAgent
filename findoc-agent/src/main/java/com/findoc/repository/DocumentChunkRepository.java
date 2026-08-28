package com.findoc.repository;

import com.findoc.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
    @Query("select c from DocumentChunk c where c.document.id = :documentId and c.tenant.id = :tenantId order by c.chunkIndex asc")
    List<DocumentChunk> findByDocumentIdAndTenantIdOrderByChunkIndexAsc(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId);
}
