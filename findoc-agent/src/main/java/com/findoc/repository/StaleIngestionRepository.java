package com.findoc.repository;

import com.findoc.messaging.IngestionJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class StaleIngestionRepository {
    private final JdbcTemplate jdbcTemplate;

    public StaleIngestionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IngestionJob> findStaleJobs(long leaseMillis, int limit) {
        return jdbcTemplate.query("""
            select id, tenant_id, user_id
            from documents
            where status = 'PROCESSING' and deleted_at is null
              and updated_at < current_timestamp - (? * interval '1 millisecond')
            order by updated_at, id
            limit ?
            """, (resultSet, row) -> new IngestionJob(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("user_id", UUID.class)), leaseMillis, limit);
    }

    public int requeueIfStillStale(IngestionJob job, long leaseMillis) {
        return jdbcTemplate.update("""
            update documents
            set status = 'PENDING', error_msg = 'Ingestion attempt expired before completion',
                retry_count = retry_count + 1, updated_at = current_timestamp
            where id = ? and tenant_id = ? and user_id = ? and status = 'PROCESSING'
              and deleted_at is null
              and updated_at < current_timestamp - (? * interval '1 millisecond')
            """, job.documentId(), job.tenantId(), job.userId(), leaseMillis);
    }
}
