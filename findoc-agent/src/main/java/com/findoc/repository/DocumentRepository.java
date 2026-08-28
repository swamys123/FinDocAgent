package com.findoc.repository;

import com.findoc.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    @Query("select d from Document d where d.tenant.id = :tenantId and d.deletedAt is null order by d.createdAt desc")
    Page<Document> findByTenantIdAndDeletedAtIsNull(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("select d from Document d where d.id = :id and d.tenant.id = :tenantId and d.deletedAt is null")
    Optional<Document> findByIdAndTenantIdAndDeletedAtIsNull(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
