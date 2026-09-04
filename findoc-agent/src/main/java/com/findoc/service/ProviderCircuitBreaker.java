package com.findoc.service;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public final class ProviderCircuitBreaker {
    private final int failureThreshold;
    private final long openDurationMillis;
    private int failures;
    private long openedAt;
    private boolean halfOpenInFlight;

    public ProviderCircuitBreaker(int failureThreshold, Duration openDuration) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openDurationMillis = Objects.requireNonNull(openDuration, "openDuration").toMillis();
        if (openDurationMillis < 1) {
            throw new IllegalArgumentException("openDuration must be positive");
        }
    }

    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        beforeCall();
        try {
            T result = operation.get();
            afterSuccess();
            return result;
        } catch (RuntimeException exception) {
            afterFailure();
            throw exception;
        }
    }

    private synchronized void beforeCall() {
        if (openedAt == 0) {
            return;
        }
        if (System.currentTimeMillis() - openedAt < openDurationMillis || halfOpenInFlight) {
            throw new CircuitOpenException();
        }
        halfOpenInFlight = true;
    }

    private synchronized void afterSuccess() {
        failures = 0;
        openedAt = 0;
        halfOpenInFlight = false;
    }

    private synchronized void afterFailure() {
        failures++;
        halfOpenInFlight = false;
        if (failures >= failureThreshold) {
            openedAt = System.currentTimeMillis();
        }
    }

    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException() {
            super("External provider circuit is open");
        }
    }
}