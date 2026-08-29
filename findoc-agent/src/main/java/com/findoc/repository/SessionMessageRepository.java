package com.findoc.repository;

import com.findoc.entity.SessionMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SessionMessageRepository extends JpaRepository<SessionMessage, UUID> {
}
