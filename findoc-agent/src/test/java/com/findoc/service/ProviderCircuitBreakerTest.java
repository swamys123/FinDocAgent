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

    @Test
    void probeFailureInHalfOpenStateRetripsCircuitToOpen() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(2, Duration.ofMillis(10));

        // Trip the circuit to OPEN
        assertThatThrownBy(() -> breaker.execute(() -> { throw new RuntimeException("fail-1"); }))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> breaker.execute(() -> { throw new RuntimeException("fail-2"); }))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> breaker.execute(() -> "blocked"))
            .isInstanceOf(ProviderCircuitBreaker.CircuitOpenException.class);

        waitForOpenWindow();

        // HALF_OPEN probe fails
        assertThatThrownBy(() -> breaker.execute(() -> { throw new RuntimeException("probe-failed"); }))
            .isInstanceOf(RuntimeException.class);

        // Subsequent call must be blocked again
        assertThatThrownBy(() -> breaker.execute(() -> "still-blocked"))
            .isInstanceOf(ProviderCircuitBreaker.CircuitOpenException.class);
    }

    @Test
    void validatesConstructorParameters() {
        assertThatThrownBy(() -> new ProviderCircuitBreaker(0, Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failureThreshold must be positive");

        assertThatThrownBy(() -> new ProviderCircuitBreaker(-1, Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failureThreshold must be positive");

        assertThatThrownBy(() -> new ProviderCircuitBreaker(3, null))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ProviderCircuitBreaker(3, Duration.ofMillis(0)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("openDuration must be positive");
    }

    @Test
    void nullOperationThrowsNullPointerException() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(3, Duration.ofSeconds(10));
        assertThatThrownBy(() -> breaker.execute(null))
            .isInstanceOf(NullPointerException.class);
    }

    private void waitForOpenWindow() {
        long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }
}