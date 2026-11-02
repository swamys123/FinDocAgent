package com.findoc.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderTelemetryTest {
    @Test
    void recordsProviderReportedTokensAndConfiguredCost() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new ProviderTelemetry(registry, 12, 3, 9);

        telemetry.recordTokenUsage("gemini", 250_000, 0);
        telemetry.recordTokenUsage("openrouter", 100_000, 50_000);

        assertThat(registry.get("findoc.provider.tokens")
            .tags("provider", "gemini", "direction", "input").counter().count()).isEqualTo(250_000);
        assertThat(registry.get("findoc.provider.token.cost.usd")
            .tags("provider", "gemini", "direction", "input").counter().count()).isEqualTo(3);
        assertThat(registry.get("findoc.provider.token.cost.usd")
            .tags("provider", "openrouter", "direction", "input").counter().count()).isEqualTo(0.3);
        assertThat(registry.get("findoc.provider.token.cost.usd")
            .tags("provider", "openrouter", "direction", "output").counter().count()).isEqualTo(0.45);
    }

    @Test
    void recordsProviderCallDurationAndFailureOutcome() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new ProviderTelemetry(registry, 0, 0, 0);

        assertThat(telemetry.recordCall("gemini", () -> "embedding")).isEqualTo("embedding");
        assertThatThrownBy(() -> telemetry.recordCall("openrouter", () -> {
            throw new IllegalStateException("provider unavailable");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("findoc.provider.request")
            .tags("provider", "gemini", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("findoc.provider.request")
            .tags("provider", "openrouter", "outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    void exportsCircuitBreakerStateAndBlockedRequests() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new ProviderTelemetry(registry, 0, 0, 0);
        var breaker = new ProviderCircuitBreaker(1, Duration.ofSeconds(1));
        telemetry.registerCircuitBreaker("gemini", breaker);

        assertThat(registry.get("findoc.provider.circuit.breaker.state")
            .tag("provider", "gemini").gauge().value()).isEqualTo(0);
        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new IllegalStateException("provider unavailable");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("findoc.provider.circuit.breaker.state")
            .tag("provider", "gemini").gauge().value()).isEqualTo(1);
        telemetry.recordCircuitOpen("gemini");
        assertThat(registry.get("findoc.provider.circuit.breaker.blocked")
            .tag("provider", "gemini").counter().count()).isEqualTo(1);
    }
}
