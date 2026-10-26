package com.findoc.service.idempotency;

import org.springframework.http.HttpStatus;

public record IdempotencyResult<T>(HttpStatus status, T body, boolean replayed) {
}
