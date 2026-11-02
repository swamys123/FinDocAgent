package com.findoc.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class ProviderTelemetry {
    private final MeterRegistry meterRegistry;
    private final double geminiInputUsdPerMillionTokens;
    private final double openRouterInputUsdPerMillionTokens;
    private final double openRouterOutputUsdPerMillionTokens;

    public ProviderTelemetry(
            MeterRegistry meterRegistry,
            @Value("${findoc.observability.provider-cost.gemini-input-usd-per-million-tokens:0}") double geminiInputUsdPerMillionTokens,
            @Value("${findoc.observability.provider-cost.openrouter-input-usd-per-million-tokens:0}") double openRouterInputUsdPerMillionTokens,
            @Value("${findoc.observability.provider-cost.openrouter-output-usd-per-million-tokens:0}") double openRouterOutputUsdPerMillionTokens) {
        this.meterRegistry = meterRegistry;
        this.geminiInputUsdPerMillionTokens = requireNonNegative(geminiInputUsdPerMillionTokens);
        this.openRouterInputUsdPerMillionTokens = requireNonNegative(openRouterInputUsdPerMillionTokens);
        this.openRouterOutputUsdPerMillionTokens = requireNonNegative(openRouterOutputUsdPerMillionTokens);
    }

    public <T> T recordCall(String provider, Supplier<T> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return call.get();
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            sample.stop(Timer.builder("findoc.provider.request")
                .description("External provider request duration")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry));
        }
    }

    public void registerCircuitBreaker(String provider, ProviderCircuitBreaker circuitBreaker) {
        Gauge.builder("findoc.provider.circuit.breaker.state", circuitBreaker, ProviderCircuitBreaker::stateCode)
            .description("Circuit state: 0=closed, 1=open, 2=half_open")
            .tag("provider", provider)
            .register(meterRegistry);
    }

    public void recordCircuitOpen(String provider) {
        Counter.builder("findoc.provider.circuit.breaker.blocked")
            .description("Provider requests rejected while its circuit is open")
            .tag("provider", provider)
            .register(meterRegistry)
            .increment();
    }

    public void recordTokenUsage(String provider, long inputTokens, long outputTokens) {
        recordTokenUsage(provider, "input", inputTokens, inputRate(provider));
        recordTokenUsage(provider, "output", outputTokens, outputRate(provider));
    }

    private void recordTokenUsage(String provider, String direction, long tokens, double usdPerMillionTokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("Token usage cannot be negative");
        }
        if (tokens == 0) {
            return;
        }
        Counter.builder("findoc.provider.tokens")
            .description("Provider-reported token usage")
            .tag("provider", provider)
            .tag("direction", direction)
            .register(meterRegistry)
            .increment(tokens);
        if (usdPerMillionTokens > 0) {
            Counter.builder("findoc.provider.token.cost.usd")
                .description("Estimated provider token cost using configured per-million-token rates")
                .tag("provider", provider)
                .tag("direction", direction)
                .register(meterRegistry)
                .increment(tokens * usdPerMillionTokens / 1_000_000d);
        }
    }

    private double inputRate(String provider) {
        return switch (provider) {
            case "gemini" -> geminiInputUsdPerMillionTokens;
            case "openrouter" -> openRouterInputUsdPerMillionTokens;
            default -> 0;
        };
    }

    private double outputRate(String provider) {
        return "openrouter".equals(provider) ? openRouterOutputUsdPerMillionTokens : 0;
    }

    private static double requireNonNegative(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Provider token cost rates must be finite and non-negative");
        }
        return value;
    }
}
