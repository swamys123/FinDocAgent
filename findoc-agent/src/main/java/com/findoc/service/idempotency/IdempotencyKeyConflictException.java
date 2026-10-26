package com.findoc.service.idempotency;

public class IdempotencyKeyConflictException extends RuntimeException {
    public IdempotencyKeyConflictException() {
        super("Idempotency-Key was already used for a different request");
    }
}
