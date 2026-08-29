package com.findoc.repository;

import com.findoc.entity.QueryTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QueryTraceRepository extends JpaRepository<QueryTrace, UUID> {
}
