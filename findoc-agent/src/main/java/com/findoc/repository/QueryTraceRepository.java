package com.findoc.repository;

import com.findoc.entity.QueryTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface QueryTraceRepository extends JpaRepository<QueryTrace, UUID> {
	@Query("select t from QueryTrace t where t.id = :traceId and t.tenant.id = :tenantId and t.session.user.id = :userId")
	Optional<QueryTrace> findByIdAndTenantIdAndUserId(
		@Param("traceId") UUID traceId,
		@Param("tenantId") UUID tenantId,
		@Param("userId") UUID userId
	);
}
