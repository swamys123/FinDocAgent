package com.findoc.repository;

import com.findoc.entity.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {
    @Query("select s from AgentSession s where s.id = :sessionId and s.tenant.id = :tenantId and s.user.id = :userId and s.deletedAt is null")
    Optional<AgentSession> findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
        @Param("sessionId") UUID sessionId,
        @Param("tenantId") UUID tenantId,
        @Param("userId") UUID userId
    );
}
