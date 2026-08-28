package com.findoc.repository;

import com.findoc.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    @Query("select u from User u where u.username = :username and u.tenant.id = :tenantId and u.deletedAt is null")
    Optional<User> findByUsernameAndTenantIdAndDeletedAtIsNull(@Param("username") String username, @Param("tenantId") UUID tenantId);
}
