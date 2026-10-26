package com.findoc.service.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyExecutor {
    private static final int MAX_KEY_LENGTH = 255;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> IdempotencyResult<T> execute(UUID tenantId,
                                            String key,
                                            String fingerprint,
                                            HttpStatus responseStatus,
                                            Class<T> responseType,
                                            Supplier<T> operation) {
        if (key == null) {
            return new IdempotencyResult<>(responseStatus, operation.get(), false);
        }
        validateKey(key);

        jdbcTemplate.update(
            "DELETE FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ? AND expires_at <= CURRENT_TIMESTAMP",
            tenantId, key);

        int inserted = jdbcTemplate.update("""
            INSERT INTO idempotency_keys
                (tenant_id, idempotency_key, request_fingerprint, response_status, response_body, expires_at)
            VALUES (?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP + INTERVAL '24 hours')
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """, tenantId, key, fingerprint);

        if (inserted == 0) {
            return replay(tenantId, key, fingerprint, responseType);
        }

        T response = operation.get();
        String responseJson = serialize(response);
        int updated = jdbcTemplate.update("""
            UPDATE idempotency_keys
            SET response_status = ?, response_body = CAST(? AS JSONB)
            WHERE tenant_id = ? AND idempotency_key = ? AND request_fingerprint = ?
            """, responseStatus.value(), responseJson, tenantId, key, fingerprint);
        if (updated != 1) {
            throw new IllegalStateException("Unable to persist idempotent response");
        }
        return new IdempotencyResult<>(responseStatus, response, false);
    }

    @Scheduled(fixedDelayString = "${findoc.idempotency.cleanup-interval-ms:3600000}")
    @Transactional
    public void removeExpiredKeys() {
        jdbcTemplate.update("DELETE FROM idempotency_keys WHERE expires_at <= CURRENT_TIMESTAMP");
    }

    private <T> IdempotencyResult<T> replay(UUID tenantId, String key, String fingerprint, Class<T> responseType) {
        StoredResponse stored = jdbcTemplate.query("""
            SELECT request_fingerprint, response_status, response_body::text
            FROM idempotency_keys
            WHERE tenant_id = ? AND idempotency_key = ?
            """, resultSet -> {
            if (!resultSet.next()) {
                return null;
            }
            Number status = (Number) resultSet.getObject("response_status");
            return new StoredResponse(
                resultSet.getString("request_fingerprint"),
                status == null ? null : status.intValue(),
                resultSet.getString("response_body"));
        }, tenantId, key);

        if (stored == null) {
            throw new IllegalStateException("Idempotency key disappeared during request processing");
        }
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException();
        }
        if (stored.status() == null || stored.body() == null) {
            throw new IllegalStateException("Idempotency response was not committed");
        }

        return new IdempotencyResult<>(
            HttpStatus.valueOf(stored.status()),
            deserialize(stored.body(), responseType),
            true);
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize idempotent response", exception);
        }
    }

    private <T> T deserialize(String response, Class<T> responseType) {
        try {
            return objectMapper.readValue(response, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to restore idempotent response", exception);
        }
    }

    private static void validateKey(String key) {
        if (key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    private record StoredResponse(String fingerprint, Integer status, String body) {
    }
}
