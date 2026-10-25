package com.findoc.repository;

import com.findoc.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    @Query("select d from Document d where d.tenant.id = :tenantId and d.deletedAt is null order by d.createdAt desc")
    Page<Document> findByTenantIdAndDeletedAtIsNull(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("select d from Document d where d.id = :id and d.tenant.id = :tenantId and d.deletedAt is null")
    Optional<Document> findByIdAndTenantIdAndDeletedAtIsNull(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Modifying
    @Query(value = """
        update documents
        set status = 'PROCESSING', error_msg = null, updated_at = current_timestamp
        where id = :id and tenant_id = :tenantId and deleted_at is null
          and status in ('PENDING', 'FAILED')
        """, nativeQuery = true)
    int claimForIngestion(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query(value = """
        select status from documents
        where id = :id and tenant_id = :tenantId and deleted_at is null
        """, nativeQuery = true)
    Optional<String> findCurrentStatus(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Modifying
    @Query(value = """
        update documents
        set status = 'READY', page_count = :pageCount, error_msg = null, updated_at = current_timestamp
        where id = :id and tenant_id = :tenantId and deleted_at is null and status = 'PROCESSING'
        """, nativeQuery = true)
    int completeIngestion(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
                          @Param("pageCount") Integer pageCount);

    @Modifying
    @Query(value = """
        update documents
        set status = case when :exhausted then 'FAILED' else 'PENDING' end,
            error_msg = :message,
            retry_count = retry_count + case when :exhausted then 0 else 1 end,
            updated_at = current_timestamp
        where id = :id and tenant_id = :tenantId and deleted_at is null
          and status = case when :exhausted then 'PENDING' else 'PROCESSING' end
        """, nativeQuery = true)
    int recordIngestionFailure(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
                               @Param("message") String message, @Param("exhausted") boolean exhausted);
}
