package com.findoc.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.findoc.messaging.IngestionJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void enqueue(UUID tenantId, IngestionJob job) {
        enqueue(tenantId, job, Map.of());
    }

    public void enqueue(UUID tenantId, IngestionJob job, Map<String, String> traceContext) {
        try {
            String payload = objectMapper.writeValueAsString(job);
            String context = traceContext.isEmpty() ? null : objectMapper.writeValueAsString(traceContext);
            jdbcTemplate.update(
                "insert into outbox (tenant_id, payload, trace_context) values (?, cast(? as jsonb), cast(? as jsonb))",
                tenantId, payload, context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize ingestion outbox event", exception);
        }
    }

    public List<OutboxEvent> lockNextPending() {
        return jdbcTemplate.query(
            "select id, payload::text, trace_context::text from outbox "
                + "where sent_at is null order by created_at, id limit 1 for update skip locked",
            (resultSet, rowNumber) -> new OutboxEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("payload"),
                parseTraceContext(resultSet.getString("trace_context"))));
    }

    public int markSent(UUID id) {
        return jdbcTemplate.update(
            "update outbox set sent_at = current_timestamp where id = ? and sent_at is null",
            id);
    }

    private Map<String, String> parseTraceContext(String contextJson) {
        if (contextJson == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(contextJson, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize outbox trace context", exception);
        }
    }

    public record OutboxEvent(UUID id, String payload, Map<String, String> traceContext) {}
}
