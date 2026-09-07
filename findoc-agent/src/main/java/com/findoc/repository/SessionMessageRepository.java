package com.findoc.repository;

import com.findoc.entity.SessionMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SessionMessageRepository extends JpaRepository<SessionMessage, UUID> {
	@Query("select m from SessionMessage m where m.session.id = :sessionId and m.session.tenant.id = :tenantId and m.session.user.id = :userId and m.session.deletedAt is null order by m.createdAt asc")
	List<SessionMessage> findBySessionIdAndTenantIdAndUserIdOrderByCreatedAtAsc(
		@Param("sessionId") UUID sessionId,
		@Param("tenantId") UUID tenantId,
		@Param("userId") UUID userId
	);
}
