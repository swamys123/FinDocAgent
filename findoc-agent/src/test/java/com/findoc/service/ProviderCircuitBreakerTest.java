package com.findoc.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderCircuitBreakerTest {
    @Test
    void opensAfterThresholdAndAllowsProbeAfterDuration() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(2, Duration.ofMillis(10));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> breaker.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("provider failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> breaker.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("provider failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> breaker.execute(() -> "blocked"))
            .isInstanceOf(ProviderCircuitBreaker.CircuitOpenException.class);
        assertThat(attempts).hasValue(2);

        waitForOpenWindow();

        assertThat(breaker.execute(() -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void successfulCallResetsFailureCount() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(2, Duration.ofSeconds(1));

        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new IllegalStateException("provider failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(breaker.execute(() -> "healthy")).isEqualTo("healthy");
        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new IllegalStateException("provider failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(breaker.execute(() -> "healthy again")).isEqualTo("healthy again");
    }

    private void waitForOpenWindow() {
        long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }
}