package com.findoc.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class DocumentDeduplicationRepository {
    private final JdbcTemplate jdbcTemplate;

    public DocumentDeduplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lock(UUID tenantId, String contentHash) {
        String lockKey = tenantId + ":" + contentHash;
        jdbcTemplate.query(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            resultSet -> {
                resultSet.next();
                return null;
            },
            lockKey);
    }
}
